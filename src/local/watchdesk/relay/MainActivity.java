package local.watchdesk.relay;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final List<BluetoothDevice> devices=new ArrayList<>();
    private final List<String> labels=new ArrayList<>();
    private ArrayAdapter<String> adapter;
    private BluetoothAdapter bluetooth;
    private Spinner devicePicker;
    private EditText keyInput;
    private TextView faceLabel,status,detail;
    private ProgressBar progress;
    private Button scan,pick,install,cancel,importConnection;
    private TextView connectionLabel,faceTitle,deviceState,connectionState,fileState,progressValue;
    private final List<View> preparationControls=new ArrayList<>();
    private boolean transferring,applied;
    private String selectedName="L1D · 雨夜 R13";
    private static final int PAPER=Color.rgb(246,244,239),INK=Color.rgb(43,48,51),MUTED=Color.rgb(110,118,121),BLUE=Color.rgb(78,105,119),LINE=Color.rgb(226,229,226);
    private byte[] importedKey;
    private volatile BluetoothSocket socket;
    private volatile WatchClient client;
    private volatile boolean cancelled,busy;
    private volatile String phase="等待操作";
    private boolean scanning;
    private FaceFile face;
    private File selectedFile;
    private final UUID spp=UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");
    private final BroadcastReceiver discoveries=new BroadcastReceiver(){
        @Override public void onReceive(Context c,Intent i){
            if(BluetoothDevice.ACTION_FOUND.equals(i.getAction())){BluetoothDevice d=i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);if(d!=null)add(d);}
            if(BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(i.getAction())&&scanning){scanning=false;scan.setEnabled(!busy);scan.setText("搜索附近手表");if(!busy&&status.getText().toString().startsWith("正在搜索"))status.setText("搜索结束，请选择手表");updateReadiness();}
        }
    };
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        BluetoothManager manager=(BluetoothManager)getSystemService(BLUETOOTH_SERVICE);bluetooth=manager==null?null:manager.getAdapter();
        buildInterface();
        IntentFilter filter=new IntentFilter(BluetoothDevice.ACTION_FOUND);filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(discoveries,filter,Context.RECEIVER_EXPORTED);else registerReceiver(discoveries,filter);
        if(permitted())refresh();else status.setText("点击搜索，允许查找附近手表");
        try{if(Arrays.asList(getAssets().list("")).contains("bundled.face"))loadFace(null);}catch(IOException ignored){}
    }
    private void buildInterface(){
        getWindow().setStatusBarColor(Color.TRANSPARENT);getWindow().setNavigationBarColor(PAPER);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setClipToPadding(false);scroll.setBackgroundColor(PAPER);scroll.setVerticalScrollBarEnabled(false);
        scroll.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;});
        LinearLayout layout=column();layout.setPadding(dp(18),dp(18),dp(18),dp(24));scroll.addView(layout,new ScrollView.LayoutParams(-1,-2));
        LinearLayout brand=row();brand.setGravity(Gravity.CENTER_VERTICAL);
        ImageView mark=new ImageView(this);int icon=resource("ic_launcher","mipmap");if(icon==0)icon=resource("ic_watch","drawable");mark.setImageResource(icon);mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);brand.addView(mark,new LinearLayout.LayoutParams(dp(44),dp(44)));
        LinearLayout brandWords=column();LinearLayout.LayoutParams brandParams=new LinearLayout.LayoutParams(0,-2,1);brandParams.setMarginStart(dp(12));brand.addView(brandWords,brandParams);brandWords.addView(text("雨夜中转",23,true));brandWords.addView(caption("WATCH LINK  /  为腕间而来",11));
        Button help=quietButton("帮助",v->showPreparation());brand.addView(help,new LinearLayout.LayoutParams(dp(56),dp(48)));layout.addView(brand);
        TextView introduction=text("喜欢的雨夜，\n戴在手腕上。",27,true);introduction.setLineSpacing(dp(3),1);add(layout,introduction,20);
        TextView subtitle=caption("为 Xiaomi Watch S5 41mm 准备的表盘伴侣",13);add(layout,subtitle,6);

        LinearLayout hero=card(layout,18);hero.setBackground(round(Color.rgb(235,239,239),24,Color.TRANSPARENT));LinearLayout heroRow=row();heroRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout heroWords=column();heroRow.addView(heroWords,new LinearLayout.LayoutParams(0,-2,1));
        TextView edition=caption("L1D  ·  R13",12);edition.setTextColor(BLUE);edition.setLetterSpacing(.08f);heroWords.addView(edition);
        add(heroWords,text("雨夜同伴",23,true),10);TextView heroDescription=caption("轻动态表盘\nQ 版熄屏陪伴",14);heroDescription.setLineSpacing(dp(4),1);add(heroWords,heroDescription,10);
        int screen=(int)(getResources().getDisplayMetrics().widthPixels/getResources().getDisplayMetrics().density);int imageSize=Math.min(172,Math.max(120,screen-176));
        ImageView preview=new ImageView(this);int art=resource("watch_preview_r13","drawable");if(art!=0)preview.setImageResource(art);preview.setScaleType(ImageView.ScaleType.CENTER_CROP);preview.setBackground(round(Color.rgb(30,36,39),imageSize/2,Color.TRANSPARENT));preview.setOutlineProvider(new ViewOutlineProvider(){@Override public void getOutline(View v,Outline o){o.setOval(0,0,v.getWidth(),v.getHeight());}});preview.setClipToOutline(true);preview.setContentDescription("L1D 雨夜 R13 表盘效果预览，图中数值为演示值");
        LinearLayout.LayoutParams previewParams=new LinearLayout.LayoutParams(dp(imageSize),dp(imageSize));previewParams.setMarginStart(dp(12));heroRow.addView(preview,previewParams);hero.addView(heroRow);
        TextView previewNote=caption("464 × 464  ·  41 mm  ·  预览数值为示例",11);add(hero,previewNote,14);

        LinearLayout deviceCard=card(layout,14);deviceState=stepHeading(deviceCard,"01","选择手表","待选择");
        add(deviceCard,caption("先使用这台手机已有的配对设备。\n仅显示名称含 Watch 的设备。",13),12);
        devicePicker=new Spinner(this);devicePicker.setContentDescription("选择要安装表盘的蓝牙手表");devicePicker.setMinimumHeight(dp(52));devicePicker.setBackground(round(Color.rgb(247,248,246),12,LINE));
        adapter=new ArrayAdapter<String>(this,android.R.layout.simple_spinner_item,labels){
            @Override public View getView(int at,View old,ViewGroup parent){TextView v=(TextView)super.getView(at,old,parent);v.setTextColor(INK);v.setTextSize(15);v.setPadding(dp(12),dp(14),dp(12),dp(14));v.setSingleLine(false);return v;}
            @Override public View getDropDownView(int at,View old,ViewGroup parent){TextView v=(TextView)super.getDropDownView(at,old,parent);v.setTextColor(INK);v.setTextSize(15);v.setPadding(dp(18),dp(16),dp(18),dp(16));return v;}
        };adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);devicePicker.setAdapter(adapter);devicePicker.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){@Override public void onItemSelected(AdapterView<?> p,View v,int position,long id){updateReadiness();}@Override public void onNothingSelected(AdapterView<?> p){updateReadiness();}});add(deviceCard,devicePicker,12);
        scan=button(deviceCard,"搜索附近手表",v->search(),false);add(deviceCard,caption("连接时请暂停运动健康对蓝牙的占用。",12),8);

        LinearLayout connectionCard=card(layout,12);connectionState=stepHeading(connectionCard,"02","准备连接信息","待导入");
        connectionLabel=caption("从运动健康导出的文件中读取，连接信息仅保存在本机内存。",13);add(connectionCard,connectionLabel,12);
        importConnection=button(connectionCard,"导入连接信息",v->{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");i.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI,android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents","primary:Download/wearablelog"));startActivityForResult(i,13);},false);
        LinearLayout connectionLinks=row();Button instructions=quietButton("查看导出方法",v->showConnectionHelp());connectionLinks.addView(instructions,new LinearLayout.LayoutParams(0,dp(48),1));
        Button manual=quietButton("手动输入",v->{keyInput.setVisibility(keyInput.getVisibility()==View.GONE?View.VISIBLE:View.GONE);if(keyInput.getVisibility()==View.VISIBLE)keyInput.requestFocus();});connectionLinks.addView(manual,new LinearLayout.LayoutParams(0,dp(48),1));connectionCard.addView(connectionLinks);
        keyInput=new EditText(this);keyInput.setSingleLine(true);keyInput.setHint("32 位十六进制 AuthKey");keyInput.setContentDescription("高级设置，手动输入连接密钥");keyInput.setTextSize(15);keyInput.setTextColor(INK);keyInput.setHintTextColor(MUTED);keyInput.setPadding(dp(12),dp(12),dp(12),dp(12));keyInput.setMinimumHeight(dp(52));keyInput.setBackground(round(PAPER,12,LINE));keyInput.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);keyInput.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);keyInput.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);keyInput.setSaveEnabled(false);keyInput.setVisibility(View.GONE);connectionCard.addView(keyInput);keyInput.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){updateReadiness();}public void afterTextChanged(Editable e){}});

        LinearLayout fileCard=card(layout,12);fileState=stepHeading(fileCard,"03","确认表盘文件","校验中");faceTitle=text(selectedName,18,true);add(fileCard,faceTitle,14);faceLabel=caption("正在准备内置表盘…",13);add(fileCard,faceLabel,6);
        pick=button(fileCard,"选择表盘文件",v->{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");startActivityForResult(i,10);},false);
        Button fileDetails=quietButton("查看文件详情",v->{if(face==null)return;new android.app.AlertDialog.Builder(this).setTitle(selectedName).setMessage("表盘 ID  "+face.id+"\n大小  "+String.format(Locale.ROOT,"%.2f MB",face.size/1048576.0)+"\n\nSHA-256\n"+face.sha256).setPositiveButton("完成",null).show();});fileCard.addView(fileDetails,new LinearLayout.LayoutParams(-1,dp(48)));

        LinearLayout transfer=card(layout,18);LinearLayout transferHeader=row();transferHeader.setGravity(Gravity.CENTER_VERTICAL);TextView transferTitle=text("传送到手表",17,true);transferHeader.addView(transferTitle,new LinearLayout.LayoutParams(0,-2,1));progressValue=text("0%",23,true);progressValue.setTextColor(BLUE);progressValue.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);transferHeader.addView(progressValue);transfer.addView(transferHeader);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(100);progress.setProgressTintList(ColorStateList.valueOf(BLUE));progress.setProgressBackgroundTintList(ColorStateList.valueOf(LINE));progress.setContentDescription("表盘传输进度，0% ");add(transfer,progress,14);progress.getLayoutParams().height=dp(8);
        status=text("准备好后，开始连接",16,true);status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);add(transfer,status,14);
        detail=caption("传送期间请保持此页面在前台，并留意手表的配对提示。安装与应用完成后，会向手表再次确认。",13);detail.setLineSpacing(dp(3),1);add(transfer,detail,8);
        install=button(layout,"连接、安装并应用",v->begin(),true);LinearLayout.LayoutParams mainParams=(LinearLayout.LayoutParams)install.getLayoutParams();mainParams.topMargin=dp(18);mainParams.height=dp(58);install.setTextSize(17);
        cancel=quietButton("取消并断开",v->abort());cancel.setEnabled(false);layout.addView(cancel,new LinearLayout.LayoutParams(-1,dp(48)));
        TextView privacy=caption("本地处理  ·  不上传连接信息\n雨夜中转 0.2.0",11);privacy.setGravity(Gravity.CENTER);privacy.setLineSpacing(dp(5),1);add(layout,privacy,12);
        setContentView(scroll);scroll.requestApplyInsets();updateReadiness();
    }
    private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private LinearLayout row(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.HORIZONTAL);return v;}
    private void add(LinearLayout parent,View child,int margin){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(margin);parent.addView(child,p);}
    private GradientDrawable round(int color,int radius,int stroke){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));if(stroke!=Color.TRANSPARENT)d.setStroke(dp(1),stroke);return d;}
    private LinearLayout card(LinearLayout parent,int margin){LinearLayout card=column();card.setPadding(dp(16),dp(17),dp(16),dp(16));card.setBackground(round(Color.WHITE,22,Color.rgb(237,237,231)));add(parent,card,margin);return card;}
    private TextView text(String s,int size,boolean bold){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(INK);v.setTypeface(Typeface.create("sans-serif",bold?Typeface.BOLD:Typeface.NORMAL));v.setIncludeFontPadding(false);if(bold&&Build.VERSION.SDK_INT>=28)v.setAccessibilityHeading(true);return v;}
    private TextView caption(String s,int size){TextView v=text(s,size,false);v.setTextColor(MUTED);v.setLineSpacing(dp(2),1);return v;}
    private TextView stepHeading(LinearLayout parent,String index,String title,String state){LinearLayout r=row();r.setGravity(Gravity.CENTER_VERTICAL);TextView number=text(index,12,true);number.setTextColor(BLUE);number.setGravity(Gravity.CENTER);number.setBackground(round(Color.rgb(235,240,241),11,Color.TRANSPARENT));number.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);r.addView(number,new LinearLayout.LayoutParams(dp(32),dp(32)));TextView heading=text(title,17,true);LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(0,-2,1);hp.setMarginStart(dp(10));r.addView(heading,hp);TextView badge=text(state,11,false);badge.setTextColor(BLUE);badge.setPadding(dp(8),dp(6),dp(8),dp(6));badge.setBackground(round(Color.rgb(241,244,242),9,Color.TRANSPARENT));r.addView(badge);parent.addView(r);return badge;}
    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+0.5f);}
    private int resource(String name,String type){return getResources().getIdentifier(name,type,getPackageName());}
    private Button styledButton(String label,View.OnClickListener click,boolean primary,boolean quiet){Button b=new Button(this);b.setText(label);b.setTextSize(14);b.setAllCaps(false);b.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));b.setMinHeight(dp(48));b.setMinimumHeight(dp(48));b.setMinWidth(0);b.setMinimumWidth(0);b.setPadding(dp(12),dp(8),dp(12),dp(8));b.setStateListAnimator(null);int[][] states={new int[]{-android.R.attr.state_enabled},new int[]{android.R.attr.state_enabled}};b.setTextColor(new ColorStateList(states,new int[]{Color.rgb(160,169,172),primary?Color.WHITE:BLUE}));GradientDrawable background=round(primary?BLUE:quiet?Color.TRANSPARENT:Color.rgb(244,247,247),14,primary||quiet?Color.TRANSPARENT:LINE);b.setBackground(new RippleDrawable(ColorStateList.valueOf(Color.rgb(210,221,225)),background,round(Color.WHITE,14,Color.TRANSPARENT)));b.setOnClickListener(click);if(!primary)preparationControls.add(b);return b;}
    private Button button(LinearLayout l,String s,View.OnClickListener click,boolean primary){Button b=styledButton(s,click,primary,false);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(50));p.topMargin=dp(12);l.addView(b,p);return b;}
    private Button quietButton(String s,View.OnClickListener click){return styledButton(s,click,false,true);}
    private void showPreparation(){new android.app.AlertDialog.Builder(this).setTitle("连接前的小准备").setMessage("先选择这台手机已有的配对手表，并暂时暂停运动健康的蓝牙连接。\n\n如果已有配对无法建立通道，再到手表「设置 → 我的设备／系统操作 → 连接新手机」，确认提示后保持该页面重试。\n\n不需要恢复出厂，也不要主动解绑。传送期间保持手机与手表靠近。 ").setPositiveButton("知道了",null).show();}
    private void showConnectionHelp(){new android.app.AlertDialog.Builder(this).setTitle("从运动健康导出连接信息").setMessage("1. 在设备页同步手表。\n2. 我的 → 关于，连续点击顶部 App 图标约 10 次。\n3. 如弹出日志导出提示，确认导出。\n4. 回到这里，选择 Download/wearablelog 下最新 ZIP。\n\n入口可能随版本变化。连接字段只会在本机内存中使用，原日志不会保存或上传。若导出文件未包含认证信息，将无法完成连接。").setPositiveButton("知道了",null).show();}
    private void updateReadiness(){if(deviceState!=null)deviceState.setText(scanning?"搜索中":devicePicker!=null&&devicePicker.getSelectedItemPosition()>=0&&!devices.isEmpty()?"已选择":"待选择");if(connectionState!=null)connectionState.setText(importedKey!=null||keyInput!=null&&keyInput.length()>0?"已准备":"待导入");if(fileState!=null)fileState.setText(face!=null?"已校验":"待选择");}
    private void updateProgress(int percent){int value=Math.max(0,Math.min(100,percent));progress.setProgress(value,true);progressValue.setText(value+"%");progress.setContentDescription("表盘传输进度，"+value+"%");}
    private String displayName(Uri uri){if(uri==null)return "L1D · 雨夜 R13";try(Cursor cursor=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(cursor!=null&&cursor.moveToFirst()){String name=cursor.getString(0);if(name!=null&&!name.trim().isEmpty())return name;}}catch(RuntimeException ignored){}return "已选择的表盘";}
    private boolean permitted(){
        if(Build.VERSION.SDK_INT>=31)return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED&&checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED;
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;
    }
    private void search(){
        if(!permitted()){requestPermissions(Build.VERSION.SDK_INT>=31?new String[]{Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_SCAN}:new String[]{Manifest.permission.ACCESS_FINE_LOCATION},11);return;}
        if(bluetooth==null){status.setText("此手机不支持蓝牙");return;}
        if(!bluetooth.isEnabled()){startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),12);return;}
        refresh();bluetooth.cancelDiscovery();
        if(bluetooth.startDiscovery()){scanning=true;scan.setEnabled(false);scan.setText("正在搜索附近手表…");status.setText("正在搜索，请让手表处于可连接状态");updateReadiness();main.postDelayed(()->{if(!busy&&permitted()&&bluetooth.isDiscovering())bluetooth.cancelDiscovery();},15000);}
        else status.setText("搜索未启动，可从已配对列表选择手表");
    }
    private void refresh(){if(bluetooth==null||!permitted())return;devices.clear();labels.clear();for(BluetoothDevice d:bluetooth.getBondedDevices())add(d);adapter.notifyDataSetChanged();updateReadiness();}
    private void add(BluetoothDevice d){if(!permitted()||devices.contains(d)||busy)return;String name=d.getName();if(name==null||!name.toLowerCase(Locale.ROOT).contains("watch"))return;devices.add(d);String address=d.getAddress();labels.add(name+" · "+address.substring(Math.max(0,address.length()-5)));adapter.notifyDataSetChanged();updateReadiness();}
    @Override public void onRequestPermissionsResult(int request,String[] p,int[] grants){super.onRequestPermissionsResult(request,p,grants);if(request==11){if(permitted())search();else status.setText("没有附近设备权限，无法连接手表");}}
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);if(request==12){if(permitted())search();return;}
        if(request==13&&result==RESULT_OK&&data!=null&&data.getData()!=null){loadConnection(data.getData());return;}
        if(request!=10||result!=RESULT_OK||data==null||data.getData()==null)return;
        loadFace(data.getData());
    }
    private void loadConnection(Uri uri){
        setBusy(true);cancel.setEnabled(false);status.setText("在手机本地读取连接字段");
        worker.execute(()->{try(InputStream in=getContentResolver().openInputStream(uri)){
            if(in==null)throw new IOException("无法读取选中的连接日志");byte[] key=ConnectionImport.read(in);
            main.post(()->{if(isDestroyed()){Arrays.fill(key,(byte)0);return;}clearImported();importedKey=key;connectionLabel.setText("连接信息已准备好。密钥仅在本机内存中使用，不会显示或上传。");status.setText("连接信息已准备好");setBusy(false);});
        }catch(Exception e){main.post(()->{if(isDestroyed())return;clearImported();connectionLabel.setText("连接信息未导入");status.setText(message(e));setBusy(false);});}});
    }
    private void loadFace(Uri uri){
        setBusy(true);cancel.setEnabled(false);status.setText("校验表盘格式和摘要");
        worker.execute(()->{File temp=null;try{
            temp=File.createTempFile("selected-",".face",getCacheDir());int size=0;
            try(InputStream in=uri==null?getAssets().open("bundled.face"):getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(temp)){
                if(in==null)throw new IOException("无法读取选中文件");byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1){size+=n;if(size>FaceFile.MAX_BYTES)throw new IOException("表盘超过 64MB");out.write(b,0,n);}
            }
            FaceFile parsed=new FaceFile(temp);File ready=temp;String name=displayName(uri);main.post(()->{if(isDestroyed()){ready.delete();return;}if(selectedFile!=null)selectedFile.delete();selectedFile=ready;face=parsed;selectedName=name;faceTitle.setText(name);faceLabel.setText("Q63 原生表盘 · "+String.format(Locale.ROOT,"%.2f MB",parsed.size/1048576.0)+"\nID "+parsed.id+" · SHA-256 已校验");status.setText("表盘已就绪，可开始连接");setBusy(false);});
        }catch(Exception e){if(temp!=null)temp.delete();main.post(()->{if(isDestroyed())return;status.setText("文件无效："+message(e));setBusy(false);});}});
    }
    private void begin(){
        if(busy)return;if(!permitted()){search();return;}if(bluetooth==null||!bluetooth.isEnabled()){status.setText("请先打开蓝牙");return;}
        if(face==null){status.setText("请先选择表盘文件");return;}
        int at=devicePicker.getSelectedItemPosition();if(at<0||at>=devices.size()){status.setText("请先选择手表");return;}
        byte[] auth;
        try{String manual=keyInput.getText().toString().trim();if(!manual.isEmpty())auth=Crypto.unhex(manual);else if(importedKey!=null)auth=importedKey.clone();else throw new IllegalArgumentException("请先导入运动健康连接日志，无需手填密钥");if(auth.length!=16)throw new IllegalArgumentException("AuthKey 必须为 32 位十六进制");}catch(Exception e){status.setText(message(e));return;}
        keyInput.getText().clear();cancelled=false;scanning=false;transferring=true;applied=false;phase="蓝牙连接";setBusy(true);updateProgress(0);status.setText("正在连接手表");detail.setText("正在使用已有配对建立蓝牙通道。请保持手机与手表靠近，并留意手表上的配对提示。");
        BluetoothDevice device=devices.get(at);FaceFile chosen=face;
        worker.execute(()->{WatchClient active=null;try{
            bluetooth.cancelDiscovery();
            BluetoothSocket connecting=ConnectionAttempts.open(
                secure->secure?device.createRfcommSocketToServiceRecord(spp):device.createInsecureRfcommSocketToServiceRecord(spp),
                BluetoothSocket::connect,()->cancelled,s->socket=s,
                (secure,attempt)->main.post(()->status.setText("蓝牙连接 "+attempt+"/2 · "+(secure?"配对通道":"兼容通道"))),15000);
            if(cancelled)throw new IOException("已取消");
            phase="协议握手";main.post(()->status.setText("蓝牙已连接，正在进行协议握手"));
            SarLink link=new SarLink(connecting.getInputStream(),connecting.getOutputStream(),connecting);link.start();
            active=new WatchClient(link,(stage,percent)->{phase=stage;main.post(()->{if(!cancelled){status.setText(stage);updateProgress(percent);}});});client=active;
            active.authenticate(auth);Arrays.fill(auth,(byte)0);active.install(chosen);
            main.post(()->{applied=true;updateProgress(100);detail.setText("手表已确认：表盘 "+chosen.id+" 已安装且为当前表盘。抬腕查看雨夜，再试试熄屏效果。");});
        }catch(Exception e){boolean confirmed=active!=null&&active.installed;String failedAt=phase;main.post(()->{status.setText(cancelled?"已取消并断开":(confirmed?"已安装，应用未确认：":"失败阶段："+failedAt+"\n")+message(e));detail.setText(failedAt.equals("蓝牙连接")||failedAt.equals("协议握手")?"蓝牙／握手失败不等于密钥错误。请确认手表已进入「连接新手机」，运动健康暂时不占用连接，再重试；不要解绑或恢复出厂。":"只有明确提示密钥不匹配时，才需要重新导入对应绑定的连接信息。未收到完整成功确认时，请先在手表上核对状态。");});}
        finally{Arrays.fill(auth,(byte)0);try{if(active!=null)active.close();else if(socket!=null)socket.close();}catch(IOException ignored){}client=null;socket=null;main.post(()->{transferring=false;setBusy(false);});}});
    }
    private static String message(Exception e){String s=e.getMessage();return s==null?e.getClass().getSimpleName():s;}
    private void setBusy(boolean value){busy=value;for(View control:preparationControls)control.setEnabled(!value);scan.setEnabled(!value&&!scanning);pick.setEnabled(!value);importConnection.setEnabled(!value);install.setEnabled(!value);install.setAlpha(value?.6f:1f);install.setText(value?(transferring?"正在传送，请稍候…":"正在准备，请稍候…"):(applied?"再次连接并应用":"连接、安装并应用"));devicePicker.setEnabled(!value);keyInput.setEnabled(!value);cancel.setEnabled(value&&transferring);updateReadiness();if(value)getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);}
    private void clearImported(){if(importedKey!=null){Arrays.fill(importedKey,(byte)0);importedKey=null;}}
    private void abort(){cancelled=true;keyInput.getText().clear();clearImported();connectionLabel.setText("连接信息已清除");BluetoothSocket s=socket;if(s!=null)try{s.close();}catch(IOException ignored){}status.setText("正在取消并断开");}
    @Override protected void onDestroy(){abort();if(bluetooth!=null&&permitted())bluetooth.cancelDiscovery();try{unregisterReceiver(discoveries);}catch(IllegalArgumentException ignored){}worker.shutdownNow();if(selectedFile!=null)selectedFile.delete();super.onDestroy();}
}
