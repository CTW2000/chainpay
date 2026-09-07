package com.chainpay.chain.wallet;

import java.math.BigInteger;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.math.ec.ECCurve;
import org.bouncycastle.math.ec.ECPoint;

/**
 * 曲线参数一处定义：曲线、生成元 G、阶 n。曲线运算全部交给 BouncyCastle，我们只写派生逻辑——
 * 密码学里「自己写曲线乘法」是绝对不做的事，「自己写 30 行派生」是可以用规范向量钉住的事。
 */
public final class Secp256k1 {

    private static final X9ECParameters PARAMS = CustomNamedCurves.getByName("secp256k1");

    private Secp256k1() {}

    public static ECCurve curve() {
        return PARAMS.getCurve();
    }

    public static ECPoint g() {
        return PARAMS.getG();
    }

    public static BigInteger n() {
        return PARAMS.getN();
    }

    /** point(k) = k · G。私钥到公钥的那一步单向函数。 */
    static ECPoint point(BigInteger k) {
        return PARAMS.getG().multiply(k).normalize();
    }
}
