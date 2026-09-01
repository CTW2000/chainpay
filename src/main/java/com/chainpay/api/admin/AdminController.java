package com.chainpay.api.admin;

import com.chainpay.api.ApiResponse;
import com.chainpay.api.admin.AdminService.CredentialSummary;
import com.chainpay.api.admin.AdminService.IssuedCredential;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 控制面接口：开户、发钥匙、吊销、停用。
 *
 * <p><b>路径前缀 {@code /admin/} 不是装饰，它是安全边界。</b>
 * {@link AdminAuthFilter} 按这个前缀决定拦不拦；
 * {@code ApiKeyAuthFilter} 按 {@code /api/} 前缀决定拦不拦。
 * 两个前缀对应两套完全独立的认证，谁也进不了谁的地盘。
 *
 * <p>把管理接口放在 {@code /api/admin/...} 会同时踩中两个门卫的规则，
 * 那是最容易出错的写法 —— 路径前缀承担了访问控制的职责，就必须泾渭分明。
 */
@RestController
@RequestMapping("/admin/v1")
public class AdminController {

    private final AdminService admin;

    public AdminController(AdminService admin) {
        this.admin = admin;
    }

    // ==================================================================

    /** 创建商户的请求体。校验放在这里，坏数据到不了 service。 */
    public record CreateMerchantRequest(
            @NotBlank @Size(max = 64) String code,
            @NotBlank @Size(max = 128) String name) {}

    public record CreateMerchantResponse(long merchantId, String code, String name) {}

    /** 发凭证的请求体。label 是给人看的名字，轮换时靠它分辨该吊销哪一把。 */
    public record IssueCredentialRequest(@Size(max = 64) String label) {}

    // ==================================================================

    @PostMapping("/merchants")
    public ResponseEntity<ApiResponse<CreateMerchantResponse>> createMerchant(
            @Valid @RequestBody CreateMerchantRequest request) {
        long id = admin.createMerchant(request.code(), request.name());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(
                        new CreateMerchantResponse(id, request.code(), request.name())));
    }

    /**
     * 发一把新凭证。
     *
     * <p>响应里的 {@code secret} 是<b>这辈子唯一一次</b>出现明文的地方 ——
     * 没有任何接口能再把它查出来。丢了只能换一把新的。
     */
    @PostMapping("/merchants/{merchantId}/credentials")
    public ResponseEntity<ApiResponse<IssuedCredential>> issueCredential(
            @PathVariable long merchantId,
            @Valid @RequestBody IssueCredentialRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(admin.issueCredential(merchantId, request.label())));
    }

    /** 列出商户全部凭证。响应里<b>没有</b> secret —— 明文不会第二次出现。 */
    @GetMapping("/merchants/{merchantId}/credentials")
    public ApiResponse<List<CredentialSummary>> listCredentials(@PathVariable long merchantId) {
        return ApiResponse.ok(admin.listCredentials(merchantId));
    }

    /**
     * 吊销一把凭证。
     *
     * <p>用 POST 而不是 DELETE：这条记录<b>不会被删除</b>，
     * 只是状态改成 REVOKED。用 DELETE 会让人以为行被删了，
     * 而「删凭证」恰恰是我们明确不做的事（会断掉审计链）。
     * <b>HTTP 方法应当反映真实发生的事，不是操作听起来像什么。</b>
     */
    @PostMapping("/credentials/{credentialId}/revoke")
    public ResponseEntity<Void> revokeCredential(@PathVariable long credentialId) {
        admin.revokeCredential(credentialId);
        return ResponseEntity.noContent().build();
    }

    /** 停用商户 —— 他名下所有凭证同时失效。 */
    @PostMapping("/merchants/{merchantId}/suspend")
    public ResponseEntity<Void> suspendMerchant(@PathVariable long merchantId) {
        admin.suspendMerchant(merchantId);
        return ResponseEntity.noContent().build();
    }
}
