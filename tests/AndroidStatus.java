package local.watchdesk.relay.tests;

import android.app.*;
import android.os.*;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.*;

/** Read-only status monitor, instrumenting its own package so the relay stays alive. */
public final class AndroidStatus extends Instrumentation {
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){
        Bundle result=new Bundle();
        try{
            UiAutomation automation=getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            AccessibilityNodeInfo root=automation.getRootInActiveWindow();
            List<String> status=new ArrayList<>();collect(root,status,0);
            result.putString("relayStatus",status.isEmpty()?"No visible relay status":status.toString());
            finish(Activity.RESULT_OK,result);
        }catch(Throwable e){result.putString("monitorError",e.getClass().getSimpleName());finish(Activity.RESULT_CANCELED,result);}
    }
    private static void collect(AccessibilityNodeInfo n,List<String> found,int depth){
        if(n==null||depth>40||found.size()>24)return;
        CharSequence pkg=n.getPackageName(),text=n.getText();
        if(pkg!=null&&pkg.toString().equals("local.watchdesk.relay")&&!n.isPassword()&&text!=null){
            String s=text.toString();
            for(String key:new String[]{"失败阶段","认证成功","已连接","传输表盘","等待手表","手表已确认","蓝牙连接","协议握手","验证手表","请求手表","实验版","未发送表盘"})
                if(s.contains(key)){found.add(s.length()>800?s.substring(0,800):s);break;}
        }
        for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo child=n.getChild(i);collect(child,found,depth+1);if(child!=null)child.recycle();}
    }
}
