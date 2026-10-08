package local.watchdesk.relay.tests;

import android.app.*;
import android.content.Intent;
import android.os.*;
import android.view.*;
import android.widget.TextView;
import java.io.InputStream;
import java.security.MessageDigest;

/** Installed separately, signed with the same local key; never ships in the user APK. */
public final class AndroidSmoke extends Instrumentation {
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){new Thread(()->{
        Bundle result=new Bundle();
        try{
            Intent intent=new Intent().setClassName("local.watchdesk.relay","local.watchdesk.relay.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Activity app=startActivitySync(intent);String[] ui={""};
            for(int attempt=0;attempt<100;attempt++){
                waitForIdleSync();runOnMainSync(()->ui[0]=texts(app.getWindow().getDecorView()));
                if(ui[0].contains("941006213"))break;
                Thread.sleep(100);
            }
            MessageDigest hash=MessageDigest.getInstance("SHA-256");try(InputStream in=getTargetContext().getAssets().open("bundled.face")){byte[] block=new byte[32768];int n;while((n=in.read(block))!=-1)hash.update(block,0,n);}StringBuilder sha=new StringBuilder();for(byte b:hash.digest())sha.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
            if(!ui[0].contains("雨夜中转")||!ui[0].contains("941006213")||!ui[0].contains("SHA-256 已校验")||!sha.toString().equals("c54d392dd162d92cc35974f47331fb203231f855aaf4d6a74c96524b0942e5b7"))throw new AssertionError("UI or bundled R13 validation missing");
            result.putString("status","PASS: activity starts; bundled R13 ID and asset SHA-256 validated");
            result.putInt("actualSdk",Build.VERSION.SDK_INT);result.putString("watchInstall","NOT TESTED: AuthKey required");finish(Activity.RESULT_OK,result);
        }catch(Throwable e){result.putString("status","FAIL: "+e.getClass().getSimpleName()+": "+e.getMessage());finish(Activity.RESULT_CANCELED,result);}
    },"relay-smoke").start();}
    private static String texts(View v){StringBuilder s=new StringBuilder();if(v instanceof TextView)s.append(((TextView)v).getText()).append('\n');if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)s.append(texts(g.getChildAt(i)));}return s.toString();}
}
