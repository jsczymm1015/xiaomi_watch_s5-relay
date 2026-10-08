package local.watchdesk.relay;

import java.io.IOException;
import java.util.Locale;

/** Q63 identity from product codename, or exact model when older firmware omits it. */
public final class TargetProfile {
    public static String verify(Proto.Node device)throws IOException {
        String model=device.has(4)?device.text(4).trim():"", product=device.has(5)?device.text(5).trim():"";
        String normalized=model.toLowerCase(Locale.ROOT).replace(" ","");
        boolean exactModel=normalized.equals("m2606w1")||normalized.equals("q63")||normalized.equals("xiaomi.watch.q63")
            ||normalized.equals("xiaomiwatchs541mm")||normalized.equals("watchs541mm");
        if(product.equalsIgnoreCase("q63"))return (model.isEmpty()?"S5 41mm":shortText(model))+" / Q63";
        if(product.isEmpty()&&exactModel)return shortText(model)+" / Q63（型号识别）";
        throw new IOException("认证已通过，但未确认 Q63 型号。型号："+(model.isEmpty()?"未返回":shortText(model))+"；产品代号："+(product.isEmpty()?"未返回":shortText(product))+"；响应字段："+device.fieldNumbers()+"。未发送表盘。");
    }
    private static String shortText(String s){return s.length()>80?s.substring(0,80):s;}
}
