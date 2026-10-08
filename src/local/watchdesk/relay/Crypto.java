package local.watchdesk.relay;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;

public final class Crypto {
    public static byte[] concat(byte[]... parts) {
        int size = 0; for (byte[] p : parts) size += p.length;
        byte[] out = new byte[size]; int at=0;
        for (byte[] p : parts) { System.arraycopy(p,0,out,at,p.length); at += p.length; } return out;
    }
    public static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key,"HmacSHA256")); return mac.doFinal(data);
    }
    public static byte[] derive(byte[] key, byte[] phone, byte[] watch) throws GeneralSecurityException {
        if (key.length != 16 || phone.length != 16 || watch.length != 16) throw new GeneralSecurityException("认证参数长度错误");
        byte[] prk = hmac(concat(phone,watch),key);
        byte[] info = "miwear-auth".getBytes(StandardCharsets.US_ASCII);
        byte[] a = hmac(prk,concat(info,new byte[]{1}));
        byte[] b = hmac(prk,concat(a,info,new byte[]{2}));
        Arrays.fill(prk,(byte)0); return concat(a,b);
    }
    public static byte[] ctr(byte[] key, byte[] plain) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/CTR/NoPadding");
        c.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new IvParameterSpec(key)); return c.doFinal(plain);
    }
    /** CCM with 12-byte nonce, no AAD, 4-byte tag (RFC 3610, L=3). */
    public static byte[] ccm(byte[] key, byte[] nonce, byte[] plain) throws GeneralSecurityException {
        if (nonce.length != 12 || key.length != 16 || plain.length >= 0x1000000) throw new GeneralSecurityException("CCM 参数错误");
        Cipher aes = Cipher.getInstance("AES/ECB/NoPadding"); aes.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"));
        byte[] b = new byte[16]; b[0] = 10; System.arraycopy(nonce,0,b,1,12); counter(b,plain.length);
        byte[] state = aes.doFinal(b);
        for (int at=0; at<plain.length; at+=16) {
            Arrays.fill(b,(byte)0); System.arraycopy(plain,at,b,0,Math.min(16,plain.length-at));
            for (int i=0;i<16;i++) b[i] ^= state[i]; state = aes.doFinal(b);
        }
        byte[] out = new byte[plain.length+4]; Arrays.fill(b,(byte)0); b[0]=2; System.arraycopy(nonce,0,b,1,12);
        byte[] s0 = aes.doFinal(b);
        for (int i=0;i<4;i++) out[plain.length+i]=(byte)(state[i]^s0[i]);
        for (int at=0, block=1; at<plain.length; at+=16,block++) {
            counter(b,block); byte[] stream=aes.doFinal(b);
            for (int i=0;i<Math.min(16,plain.length-at);i++) out[at+i]=(byte)(plain[at+i]^stream[i]);
        }
        return out;
    }
    private static void counter(byte[] b,int n) { b[13]=(byte)(n>>>16); b[14]=(byte)(n>>>8); b[15]=(byte)n; }
    public static byte[] unhex(String s) {
        if ((s.length() & 1) != 0 || !s.matches("[0-9a-fA-F]+")) throw new IllegalArgumentException("AuthKey 必须为 32 位十六进制");
        byte[] out=new byte[s.length()/2]; for(int i=0;i<out.length;i++) out[i]=(byte)Integer.parseInt(s.substring(i*2,i*2+2),16); return out;
    }
    public static String hex(byte[] b) { StringBuilder s=new StringBuilder(); for(byte v:b) s.append(String.format(java.util.Locale.ROOT,"%02x",v&255)); return s.toString(); }
}
