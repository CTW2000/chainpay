package com.chainpay.audit.service;

import com.chainpay.audit.domain.AuditFinding;
import com.chainpay.audit.domain.AuditKind;
import com.chainpay.audit.domain.AuditResult;
import com.chainpay.audit.repository.AuditRepository;
import com.chainpay.audit.repository.AuditRepository.ConfirmedPayout;
import com.chainpay.audit.repository.AuditRepository.CreditedDeposit;
import com.chainpay.audit.repository.AuditRepository.Head;
import com.chainpay.audit.repository.AuditRepository.Inflow;
import com.chainpay.audit.repository.AuditRepository.Outflow;
import com.chainpay.audit.repository.AuditRepository.Token;
import com.chainpay.chain.deposit.repository.DepositRepository;
import com.chainpay.chain.erc20.Erc20Calls;
import com.chainpay.chain.rpc.BlockHeader;
import com.chainpay.chain.rpc.ChainReader;
import com.chainpay.chain.rpc.Hex;
import com.chainpay.chain.rpc.JsonRpcException;
import com.chainpay.ledger.service.LedgerAmounts;
import com.chainpay.ledger.system.SystemLedger;
import com.chainpay.ledger.system.TransientDbFailure;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 对账：站在两个节点都认的 finalized 块 F 上，把链上事实和库内记录逐项比对。只读、只报，不改任何业务表。
 * <ol>
 *   <li>脚下的块 F：库里的 finalized 与索引书签中较小的那个（索引器追赶时证据只到书签），再向两个节点各取一次 F 的块头，哈希不等于库里的就不给结论（FAILED）。</li>
 *   <li>ADDRESS_BALANCE：每个收款地址与热钱包，链上 balanceOf(F)（两节点都问，不一致 = DISPUTED）vs 库里主分支日志的转入减转出。</li>
 *   <li>DEPOSIT_LEDGER：每笔 CREDITED 入账的日志必须在主分支且块 ≤ F、账本转账金额等于入账金额；每条 FINAL 够久的收款日志必须有入账行。</li>
 *   <li>PAYOUT_LEDGER：每笔 CONFIRMED 提现恰好一次 MINED 尝试、未 revert、链上有从热钱包到收款地址、金额相等的日志；每条热钱包发出的日志必须对应一次尝试。</li>
 *   <li>CUSTODY_TOTAL：链上托管 C = Σ 余额；等式 C = 镜像(截至 F) + 已 FINAL 未入账 − 已上链未结算 + 已登记注资 + E；镜像用入账减结算推到 F 时刻，与链上余额同刻；E ≠ 0 报出来；登记过的注资日志不在主分支也报。</li>
 *   <li>LEDGER_JUDGE：ledger_judge()。</li>
 * </ol>
 * 每一轮都落一行 audit_run（OK / DIFF / FAILED）：「没跑」由上次结论距今判断。
 */
public final class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    public record Status(Optional<AuditResult> lastRun, boolean stale) {}

    private final SystemLedger system;
    private final ChainReader primary;
    private final ChainReader audit;
    private final Erc20Calls primaryCalls;
    private final Erc20Calls auditCalls;
    private final int lagBlocks;

    public AuditService(SystemLedger system, ChainReader primary, ChainReader audit, int lagBlocks) {
        this.system = system;
        this.primary = primary;
        this.audit = audit;
        this.primaryCalls = new Erc20Calls(primary);
        this.auditCalls = new Erc20Calls(audit);
        this.lagBlocks = lagBlocks;
    }

    public AuditResult runOnce() {
        Instant started = Instant.now();
        Head head = null;
        try {
            Optional<Head> stored = read(AuditRepository::standingBlock);
            if (stored.isEmpty()) {
                return record(started, "FAILED", null, List.of(), "库里没有链头：索引器还没跑过，没有 finalized 块可站");
            }
            head = stored.get();
            BlockHeader p = primary.block(head.number());
            BlockHeader a = audit.block(head.number());
            if (!p.hash().equalsIgnoreCase(head.hash()) || !a.hash().equalsIgnoreCase(head.hash())) {
                return record(started, "FAILED", head, List.of(), "两个节点对" + head.basis() + "块 " + head.number() + " 的哈希意见不同（库里 " + head.hash()
                        + "，主节点 " + p.hash() + "，审计节点 " + a.hash() + "）：在分叉上对账对出来的差异是假的，不给结论");
            }
            List<AuditFinding> findings = new ArrayList<>();
            Map<String, BigInteger> onChain = new HashMap<>();
            List<Token> tokens = read(AuditRepository::activeTokens);
            addressBalances(head.number(), tokens, findings, onChain);
            depositLedger(head.number(), findings);
            payoutLedger(head.number(), findings);
            custodyTotal(head.number(), tokens, findings, onChain);
            orphanedFundings(findings);
            judge(findings);
            String where = "站在" + head.basis() + "块 " + head.number() + ("书签".equals(head.basis()) ? "（索引器还没追到 finalized，证据只到书签）" : "");
            String summary = (findings.isEmpty() ? "账链一致" : findings.size() + " 处差异：" + findings.stream().map(f -> f.check() + "/" + f.kind()).distinct().toList()) + "；" + where;
            return record(started, findings.isEmpty() ? "OK" : "DIFF", head, findings, summary);
        } catch (JsonRpcException e) {
            return record(started, "FAILED", head, List.of(), "节点失败：" + e.getMessage());
        } catch (RuntimeException e) {
            if (TransientDbFailure.isTransient(e)) {
                return record(started, "FAILED", head, List.of(), "数据库瞬时失败：" + e.getMessage());
            }
            log.error("对账异常", e);
            return record(started, "FAILED", head, List.of(), "对账异常：" + e);
        }
    }

    /** 上次结论是什么、判官是不是沉默太久：从没给过结论 = stale；上次 OK / DIFF 距今超过两个周期 = stale。 */
    public Status status(Duration interval) {
        Optional<AuditResult> last = read(AuditRepository::lastRun);
        Optional<Instant> verdictAt = read(AuditRepository::lastVerdictAt);
        boolean stale = verdictAt.map(t -> t.isBefore(Instant.now().minus(interval.multipliedBy(2)))).orElse(true);
        return new Status(last, stale);
    }

    // ---------------------------------------------------------------- 五条检查

    private void addressBalances(long f, List<Token> tokens, List<AuditFinding> findings, Map<String, BigInteger> onChain) {
        String tag = Hex.fromLong(f);
        List<String> hotWallets = read(AuditRepository::hotWallets);
        for (Token t : tokens) {
            List<String> holders = new ArrayList<>(read(r -> r.depositAddresses(t.address())));
            holders.addAll(hotWallets);
            for (String holder : holders) {
                String role = hotWallets.contains(holder) ? "热钱包" : "收款地址";
                BigInteger expected = system.inTransaction(s -> new DepositRepository(s.jdbc()).netTransfersUpTo(holder, t.address(), f));   // 与入账核余额同一条 SQL
                BigInteger onPrimary = primaryCalls.balanceOf(t.address(), holder, tag);
                BigInteger onAudit = auditCalls.balanceOf(t.address(), holder, tag);
                String subject = role + " " + holder + " · " + t.symbol();
                if (!onPrimary.equals(onAudit)) {
                    findings.add(new AuditFinding("ADDRESS_BALANCE", AuditKind.DISPUTED, subject, onPrimary.toString(), onAudit.toString(),
                            "两个节点对块 " + f + " 上的余额意见不同：主节点 " + onPrimary + "，审计节点 " + onAudit + "；不下结论，等人看"));
                    continue;
                }
                onChain.put(t.address() + "|" + holder.toLowerCase(Locale.ROOT), onPrimary);
                if (!onPrimary.equals(expected)) {
                    boolean chainHasMore = onPrimary.compareTo(expected) > 0;
                    findings.add(new AuditFinding("ADDRESS_BALANCE", chainHasMore ? AuditKind.MISSING_IN_LEDGER : AuditKind.MISSING_ON_CHAIN, subject,
                            expected.toString(), onPrimary.toString(),
                            chainHasMore ? "链上余额比库里主分支日志的转入减转出多 " + onPrimary.subtract(expected) + "（原始单位）：漏了转入日志，或没有事件的铸币"
                                         : "链上余额比库里主分支日志的转入减转出少 " + expected.subtract(onPrimary) + "（原始单位）：留着的日志链上不认（重组没回滚？），或漏了转出"));
                }
            }
        }
    }

    private void depositLedger(long f, List<AuditFinding> findings) {
        for (CreditedDeposit d : read(AuditRepository::creditedDeposits)) {
            String subject = "入账 " + d.id() + "（日志 " + d.blockHash() + "#" + d.logIndex() + "）";
            if (!"CANONICAL".equals(d.logStatus()) || d.logBlock() > f) {
                findings.add(new AuditFinding("DEPOSIT_LEDGER", AuditKind.MISSING_ON_CHAIN, subject, "主分支上且块 ≤ " + f,
                        d.logStatus() + " @ " + d.logBlock(), "记了账的入账，它的链上证据不在主分支或还没 finalized：重组没回滚，或手工改库"));
            }
            if (d.transferAmount() == null) {
                findings.add(new AuditFinding("DEPOSIT_LEDGER", AuditKind.MISSING_ON_CHAIN, subject, "有账本转账", "没有", "CREDITED 的入账没有账本转账"));
            } else if (d.transferAmount().compareTo(d.amount()) != 0) {
                findings.add(new AuditFinding("DEPOSIT_LEDGER", AuditKind.AMOUNT_MISMATCH, subject, "账本转账 " + LedgerAmounts.text(d.transferAmount()),
                        "入账行 " + LedgerAmounts.text(d.amount()), "入账行与账本转账的金额不同：有一边被改过"));
            }
        }
        for (Inflow in : read(r -> r.finalInflowsWithoutDeposit(f - lagBlocks))) {
            findings.add(new AuditFinding("DEPOSIT_LEDGER", AuditKind.MISSING_IN_LEDGER, "日志 " + in.blockHash() + "#" + in.logIndex() + " → " + in.toAddress(),
                    "有入账行（任何状态）", "没有", "块 " + in.block() + " 的收款日志已 finalized 超过 " + lagBlocks + " 块，入账任务没处理它：任务停了，或有人删了入账行"));
        }
    }

    private void payoutLedger(long f, List<AuditFinding> findings) {
        for (ConfirmedPayout p : read(AuditRepository::confirmedPayouts)) {
            String subject = "提现 " + p.id();
            if (p.minedAttempts() != 1 || p.attemptId() == null) {
                findings.add(new AuditFinding("PAYOUT_LEDGER", AuditKind.MISSING_ON_CHAIN, subject, "恰好一次 MINED 尝试", String.valueOf(p.minedAttempts()),
                        "CONFIRMED 的提现没有（或不止一次）上链的尝试"));
                continue;
            }
            if (p.attemptBlock() == null || p.attemptBlock() > f) {
                continue;                                                                   // 上链块在 F 之后：证据还没索到，这一轮不看（结算发生在真的 finalized 之后，不是异常）
            }
            if (Boolean.TRUE.equals(p.reverted())) {
                findings.add(new AuditFinding("PAYOUT_LEDGER", AuditKind.MISSING_ON_CHAIN, subject, "未 revert", "reverted=true",
                        "CONFIRMED 的提现，上链的那次尝试其实 revert 了：结算错了"));
            }
            if (p.settleAmount() == null || p.settleAmount().compareTo(p.amount()) != 0) {
                findings.add(new AuditFinding("PAYOUT_LEDGER", AuditKind.AMOUNT_MISMATCH, subject, "结算 " + LedgerAmounts.text(p.amount()),
                        p.settleAmount() == null ? "没有结算转账" : LedgerAmounts.text(p.settleAmount()), "提现金额与结算转账金额不同"));
            }
            if (p.logStatus() == null || !"CANONICAL".equals(p.logStatus())) {
                findings.add(new AuditFinding("PAYOUT_LEDGER", AuditKind.MISSING_ON_CHAIN, subject, "主分支上有交易 " + p.txHash() + " 的转账日志",
                        p.logStatus() == null ? "没有" : p.logStatus(), "账本已结算，链上主分支找不到这笔转账：假账，或索引器漏了"));
            } else if (!p.logTo().equalsIgnoreCase(p.toAddress()) || !p.logValue().equals(p.rawValue())) {
                findings.add(new AuditFinding("PAYOUT_LEDGER", AuditKind.AMOUNT_MISMATCH, subject, p.rawValue() + " → " + p.toAddress(),
                        p.logValue() + " → " + p.logTo(), "链上那笔转账的金额或收款人与提现不同"));
            }
        }
        for (Outflow out : read(r -> r.hotWalletOutflowsWithoutAttempt(f))) {
            findings.add(new AuditFinding("PAYOUT_LEDGER", AuditKind.MISSING_IN_LEDGER, "日志 " + out.blockHash() + "#" + out.logIndex() + " 从 " + out.from(),
                    "对应一次提现尝试", "没有", "热钱包在块 " + out.block() + " 发出了 " + out.value() + "（原始单位）给 " + out.to()
                            + "，库里没有任何一次尝试用过这个哈希：有人在别处用了这把钥匙，或手工转账"));
        }
    }

    private void custodyTotal(long f, List<Token> tokens, List<AuditFinding> findings, Map<String, BigInteger> onChain) {
        List<String> hotWallets = read(AuditRepository::hotWallets);
        for (Token t : tokens) {
            List<String> holders = new ArrayList<>(read(r -> r.depositAddresses(t.address())));
            holders.addAll(hotWallets);
            BigInteger chainCustody = BigInteger.ZERO;
            for (String holder : holders) {
                BigInteger b = onChain.get(t.address() + "|" + holder.toLowerCase(Locale.ROOT));
                if (b == null) {
                    log.warn("托管总量：{} 有地址的余额没有结论（DISPUTED），这一轮跳过这种币", t.symbol());
                    chainCustody = null;
                    break;
                }
                chainCustody = chainCustody.add(b);
            }
            if (chainCustody == null) {
                continue;
            }
            BigInteger mirror = read(r -> r.mirrorAsOf(t.address(), f));                 // 截至 F：入账 − 结算，和链上余额同一时刻
            BigInteger uncredited = read(r -> r.uncreditedInflows(t.address(), f));
            BigInteger unsettled = read(r -> r.unsettledOutflows(t.address(), f));
            BigInteger fundings = read(r -> r.registeredFundings(t.address(), f));     // 运营登记过的外部注资
            BigInteger expected = mirror.add(uncredited).subtract(unsettled).add(fundings);
            BigInteger e = chainCustody.subtract(expected);
            if (e.signum() != 0) {
                findings.add(new AuditFinding("CUSTODY_TOTAL", e.signum() > 0 ? AuditKind.MISSING_IN_LEDGER : AuditKind.MISSING_ON_CHAIN, "币 " + t.symbol(),
                        expected + "（截至 F 的镜像 " + mirror + " + 已 FINAL 未入账 " + uncredited + " − 已上链未结算 " + unsettled + " + 已登记注资 " + fundings + "）", chainCustody.toString(),
                        e.signum() > 0 ? "链上托管比账本能解释的多 " + e + "（原始单位）：外部注资没登记，或漏账" : "链上托管比账本能解释的少 " + e.negate() + "（原始单位）：假账，或钥匙在别处被用了"));
            }
        }
    }

    private void orphanedFundings(List<AuditFinding> findings) {
        for (AuditRepository.OrphanedFunding o : read(AuditRepository::orphanedFundings)) {
            findings.add(new AuditFinding("CUSTODY_TOTAL", AuditKind.MISSING_ON_CHAIN, "注资 " + o.id() + "（" + o.txHash() + "#" + o.logIndex() + "）",
                    "CANONICAL", o.logStatus(), "登记的注资，它的日志已不在主分支：登记时它是 finalized 的，之后被翻掉只能是深重组或手工改库，" + o.rawValue() + "（原始单位）已从等式里剔除"));
        }
    }

    private void judge(List<AuditFinding> findings) {
        for (Map<String, Object> row : read(AuditRepository::judge)) {
            findings.add(new AuditFinding("LEDGER_JUDGE", AuditKind.JUDGE, String.valueOf(row.get("check_name")) + " · " + row.get("subject"), "0 行", "1 行",
                    String.valueOf(row.get("detail"))));
        }
    }

    // ---------------------------------------------------------------- 落库

    private AuditResult record(Instant started, String status, Head head, List<AuditFinding> findings, String detail) {
        Long number = head == null ? null : head.number();
        String hash = head == null ? null : head.hash();
        long runId = system.inTransaction(s -> {
            AuditRepository repo = new AuditRepository(s.jdbc());
            long id = repo.insertRun(started, status, number, hash, findings.size(), detail);
            repo.insertFindings(id, findings);
            return id;
        });
        if ("FAILED".equals(status)) {
            log.error("对账 run {} 没跑完：{}", runId, detail);
        } else if (!findings.isEmpty()) {
            log.error("对账 run {} 发现 {} 处差异（finalized {}）", runId, findings.size(), number);
            findings.forEach(f -> log.error("  [{}] {} {}：{}（期望 {}，实际 {}）", f.check(), f.kind(), f.subject(), f.detail(), f.expected(), f.actual()));
        }
        return new AuditResult(runId, status, number, started, Instant.now(), findings, detail);
    }

    private <T> T read(Function<AuditRepository, T> query) {
        return system.inTransaction(s -> query.apply(new AuditRepository(s.jdbc())));
    }
}
