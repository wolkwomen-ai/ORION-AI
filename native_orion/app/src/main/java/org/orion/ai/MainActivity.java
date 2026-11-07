package org.orion.ai;

import android.app.*;
import android.os.*;
import android.content.*;
import android.database.Cursor;
import android.speech.*;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import android.graphics.*;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.graphics.Color;
import android.text.InputType;
import org.json.*;
import java.net.*;
import java.io.*;
import java.util.*;

public class MainActivity extends Activity {
    private LinearLayout messages;
    private ScrollView scroll;
    private EditText input, keyInput;
    private TextView status;
    private android.content.SharedPreferences prefs;
    private android.speech.tts.TextToSpeech tts;
    private boolean ttsReady = false, agentMode = true, handsFree = false;
    private String selectedImageData = null;
    private final Handler handler = new Handler();
    private static final int PICK_IMAGE = 81;
    private static final String API = "https://api.groq.com/openai/v1/chat/completions";
    private static final String MODEL = "qwen/qwen3.8-27b";
    private final ArrayList<JSONObject> history = new ArrayList<>();

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("arion_secure_prefs", MODE_PRIVATE);
        tts = new android.speech.tts.TextToSpeech(this, result -> {
            if (result == android.speech.tts.TextToSpeech.SUCCESS) {
                ttsReady = true;
                int r = tts.setLanguage(new Locale("uz", "UZ"));
                if (r == android.speech.tts.TextToSpeech.LANG_MISSING_DATA || r == android.speech.tts.TextToSpeech.LANG_NOT_SUPPORTED)
                    tts.setLanguage(new Locale("ru", "RU"));
                tts.setOnUtteranceProgressListener(new android.speech.tts.UtteranceProgressListener() {
                    public void onStart(String id) {}
                    public void onError(String id) {}
                    public void onDone(String id) {
                        if (handsFree) handler.postDelayed(() -> listen(), 600);
                    }
                });
            }
        });
        buildUi();
        loadHistory();
        if (messages.getChildCount() == 0)
            addMessage("ARION", "Assalomu alaykum! Men ARIONman. Matn, ovoz yoki rasm orqali savol bering. ⚙ Sozlamalarda Groq API kalitini kiriting.");
    }

    private int dp(float v) { return (int)(v * getResources().getDisplayMetrics().density + 0.5f); }
    private TextView label(String s, int size) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(size); t.setTextColor(Color.rgb(25,35,55)); t.setPadding(dp(10),dp(8),dp(10),dp(8)); return t;
    }
    private Button button(String s, Runnable r) {
        Button b = new Button(this); b.setText(s); b.setOnClickListener(v -> r.run()); return b;
    }
    private void buildUi() {
        LinearLayout root = new LinearLayout(this); root.setOrientation(1); root.setPadding(dp(10),dp(8),dp(10),dp(8)); root.setBackgroundColor(Color.rgb(245,247,252));
        LinearLayout top = new LinearLayout(this); top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = label("✦ ARION", 25); title.setTypeface(null,1); top.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        top.addView(button("☰ Menyu", this::showMenu));
        top.addView(button("⚙", this::settings));
        root.addView(top);
        status = label("Tayyor • Shaxsiy AI yordamchi", 12); root.addView(status);
        scroll = new ScrollView(this); messages = new LinearLayout(this); messages.setOrientation(1); scroll.addView(messages);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout actions = new LinearLayout(this); actions.setOrientation(0);
        input = new EditText(this); input.setHint("ARIONga yozing..."); input.setTextSize(16); input.setMinLines(1); input.setMaxLines(4); input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        actions.addView(input,new LinearLayout.LayoutParams(0,-2,1));
        actions.addView(button("📷", this::pickImage));
        actions.addView(button("🎙", this::listen));
        actions.addView(button("➤", this::send));
        root.addView(actions);
        LinearLayout bottom = new LinearLayout(this); bottom.setGravity(Gravity.CENTER);
        bottom.addView(button("🧠 Agent: ON", () -> {agentMode=!agentMode; addMessage("ARION",agentMode?"Agent rejimi yoqildi: vazifani rejalashtiraman va bajarish mumkin bo‘lgan qadamlarni ko‘rsataman.":"Oddiy suhbat rejimi yoqildi.");}));
        bottom.addView(button("🔊 Ovoz", () -> {String s=prefs.getString("last_answer","");if(!s.isEmpty())speak(s);}));
        bottom.addView(button("📞 Telefon", this::phoneMenu));
        root.addView(bottom);
        setContentView(root);
    }

    private void addMessage(String who, String text) {
        TextView v = label(who + "\n" + text, 15);
        v.setTextIsSelectable(true);
        v.setOnLongClickListener(view -> { new AlertDialog.Builder(this).setTitle(who).setItems(new String[]{"Nusxalash", "Ulashish"}, (d, which) -> { if(which==0){ android.content.ClipboardManager cm=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE); cm.setPrimaryClip(android.content.ClipData.newPlainText("ARION", text)); Toast.makeText(this,"Matn nusxalandi",Toast.LENGTH_SHORT).show(); } else { Intent share=new Intent(Intent.ACTION_SEND); share.setType("text/plain"); share.putExtra(Intent.EXTRA_TEXT,text); startActivity(Intent.createChooser(share,"Ulashish")); } }).show(); return true; });
        v.setBackgroundColor(who.equals("Siz") ? Color.rgb(222,236,255) : Color.WHITE);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1,-2); p.setMargins(0,dp(5),0,dp(5)); messages.addView(v,p);
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private void send() {
        String q=input.getText().toString().trim(); if(q.isEmpty() && selectedImageData==null) return;
        final String image=selectedImageData; selectedImageData=null;
        input.setText(""); ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(input.getWindowToken(),0);
        addMessage("Siz",q.isEmpty()?"Yuborilgan rasm":q+(image!=null?"\n[ Rasm biriktirildi ]":""));
        if(q.startsWith("/hisob ")) { try { double n=eval(q.substring(7)); addMessage("ARION", "Hisob natijasi: "+n); } catch(Exception e){addMessage("ARION","Hisoblashda xato.");} return; }
        if(q.startsWith("/qidir ")) { openWeb("https://www.google.com/search?q="+UriEncode(q.substring(7))); return; }
        if(q.equals("/vaqt")) { addMessage("ARION",new java.text.SimpleDateFormat("dd.MM.yyyy HH:mm",Locale.getDefault()).format(new Date())); return; }
        if(q.equals("/budilnik")) { try {Intent i=new Intent(android.provider.AlarmClock.ACTION_SET_ALARM);startActivity(i);}catch(Exception e){addMessage("ARION","Budilnik ochilmadi.");}return; }
        String key=prefs.getString("api_key","");
        if(key.isEmpty()){ addMessage("ARION","Avval ⚙ Sozlamalar orqali Groq API kalitini kiriting."); return; }
        status.setText("ARION ishlayapti...");
        new Thread(() -> {
            try {
                String answer=ask(q,key,image);
                runOnUiThread(() -> { status.setText("Tayyor"); addMessage("ARION",answer); prefs.edit().putString("last_answer",answer).apply(); if(handsFree)speak(answer); });
            } catch(Exception e) {
                String err=e.getMessage()==null?"Noma’lum xato":e.getMessage();
                runOnUiThread(() -> { status.setText("Xatolik"); addMessage("ARION","Xatolik: "+err); });
            }
        }).start();
    }

    private String ask(String q,String key,String imageData) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(API).openConnection();
        c.setRequestMethod("POST"); c.setConnectTimeout(20000); c.setReadTimeout(90000); c.setDoOutput(true);
        c.setRequestProperty("Authorization","Bearer "+key); c.setRequestProperty("Content-Type","application/json");
        JSONArray msgs=new JSONArray();
        JSONObject sys=new JSONObject(); sys.put("role","system");
        String memory=prefs.getString("long_memory","");
        String instructions="Sen ARION nomli shaxsiy sun’iy intellekt yordamchisisan. O‘zbek tilida ravon, samimiy va aniq javob ber. Foydalanuvchiga hurmat bilan, sodda tilda tushuntir. Bilmagan narsangni to‘qima, bajarilmagan ishni bajarildi dema. Ijodkor bo‘l: yangi g‘oyalar, ixtirolar, amaliy loyihalar va noodatiy yechimlar taklif qil; xavfsizlik, narx va amalga oshirish qiyinligini ham bahola. Rasm yuborilsa, undagi ko‘rinadigan narsalarni tahlil qil, taxminni fakt sifatida ko‘rsatma. Telefonni boshqarish kerak bo‘lsa, faqat ilovada mavjud xavfsiz amallarni va foydalanuvchi tasdig‘ini talab qiladigan amallarni aniq ajrat. Sen tirik mavjudot emassan; o‘zingni inson deb ko‘rsatma. Agent rejimida vazifani maqsad, reja, kerakli ruxsatlar va tekshiruv bosqichlariga ajrat; bu ilovada mavjud bo‘lmagan tashqi amallarni bajardim deb aytma.";
        if(agentMode) instructions+=" Foydalanuvchi topshirig‘ini imkon qadar bir necha bosqichli loyiha sifatida ko‘rib chiq va bajarish mumkin bo‘lgan keyingi qadamni aniq ko‘rsat.";
        if(!memory.trim().isEmpty()) instructions+="\nFoydalanuvchining o‘zi saqlashga rozilik bergan xotira va eslatmalar:\n"+memory;
        sys.put("content",instructions); msgs.put(sys);
        synchronized(history) { for(JSONObject h:history) msgs.put(h); }
        JSONObject user=new JSONObject(); user.put("role","user");
        if(imageData!=null) {
            JSONArray parts=new JSONArray(); JSONObject t=new JSONObject();t.put("type","text");t.put("text",q.isEmpty()?"Ushbu rasmni tahlil qil.":q);parts.put(t);
            JSONObject im=new JSONObject();im.put("type","image_url");JSONObject iu=new JSONObject();iu.put("url","data:image/jpeg;base64,"+imageData);im.put("image_url",iu);parts.put(im);user.put("content",parts);
        } else user.put("content",q);
        msgs.put(user);
        JSONObject body=new JSONObject(); body.put("model",MODEL); body.put("messages",msgs); body.put("temperature",0.6); body.put("max_tokens",2200);
        try(OutputStream os=c.getOutputStream()){os.write(body.toString().getBytes("UTF-8"));}
        int code=c.getResponseCode(); InputStream is=code>=200&&code<300?c.getInputStream():c.getErrorStream();
        ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] buf=new byte[4096]; int n; while((n=is.read(buf))!=-1) out.write(buf,0,n);
        String raw=new String(out.toByteArray(),"UTF-8"); c.disconnect();
        JSONObject result=new JSONObject(raw);
        if(code<200||code>=300) { JSONObject er=result.optJSONObject("error"); throw new Exception("Groq "+code+": "+(er==null?raw:er.optString("message",raw))); }
        String answer=result.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content","").trim();
        synchronized(history) {
            JSONObject u=new JSONObject(); u.put("role","user"); u.put("content",q.isEmpty()?"[Rasm yuborildi]":q); history.add(u);
            JSONObject a=new JSONObject(); a.put("role","assistant"); a.put("content",answer); history.add(a);
            while(history.size()>30) history.remove(0);
            JSONArray arr=new JSONArray(history); prefs.edit().putString("history",arr.toString()).apply();
        }
        return answer.isEmpty()?"ARION bo‘sh javob oldi.":answer;
    }

    private void loadHistory() {
        try { JSONArray a=new JSONArray(prefs.getString("history","[]")); for(int i=0;i<a.length();i++){JSONObject o=a.getJSONObject(i);history.add(o);addMessage(o.optString("role").equals("user")?"Siz":"ARION",o.optString("content"));} } catch(Exception ignored) {}
    }

    private void showMenu() {
        String[] items = {"＋ Yangi suhbat", "▤ Suhbat tarixini ko‘rish", "🧠 Xotirani boshqarish", "🎧 Jonli ovozli suhbat: "+(handsFree?"YOQILGAN":"o‘chiq"), "⏰ Budilnik", "📅 Kalendar / eslatma", "🗑 Barcha xabarlarni o‘chirish", "⚙ Sozlamalar", "🔊 Oxirgi javobni eshittirish"};
        new AlertDialog.Builder(this).setTitle("ARION menyusi").setItems(items,(d,w)->{
            if(w==0) { history.clear(); messages.removeAllViews(); prefs.edit().remove("history").remove("last_answer").apply(); addMessage("ARION","Yangi suhbat boshlandi. Sizga qanday yordam beray?"); }
            else if(w==1) {
                StringBuilder sb=new StringBuilder(); synchronized(history){ for(JSONObject h:history){ sb.append(h.optString("role").equals("user")?"Siz: ":"ARION: ").append(h.optString("content")).append("\n\n"); } }
                ScrollView sv=new ScrollView(this); TextView tv=label(sb.length()==0?"Hozircha suhbat tarixi bo‘sh.":sb.toString(),15); tv.setTextIsSelectable(true); sv.addView(tv);
                new AlertDialog.Builder(this).setTitle("Suhbat tarixi").setView(sv).setPositiveButton("Yopish",null).show();
            } else if(w==2) memorySettings();
            else if(w==3) {handsFree=!handsFree;addMessage("ARION",handsFree?"Jonli ovozli suhbat yoqildi. 🎙 tugmasini bosing.":"Jonli ovozli suhbat o‘chirildi.");}
            else if(w==4) {try{startActivity(new Intent(android.provider.AlarmClock.ACTION_SET_ALARM));}catch(Exception e){addMessage("ARION","Budilnik ochilmadi.");}}
            else if(w==5) {try{Intent i=new Intent(Intent.ACTION_INSERT);i.setData(android.provider.CalendarContract.Events.CONTENT_URI);startActivity(i);}catch(Exception e){addMessage("ARION","Kalendar ochilmadi.");}}
            else if(w==6) new AlertDialog.Builder(this).setTitle("Xabarlarni o‘chirish").setMessage("Barcha suhbat tarixi o‘chirilsinmi?").setNegativeButton("Bekor qilish",null).setPositiveButton("O‘chirish",(dd,ww)->{history.clear();messages.removeAllViews();prefs.edit().remove("history").remove("last_answer").apply();addMessage("ARION","Suhbat tarixi tozalandi.");}).show();
            else if(w==7) settings();
            else {String s=prefs.getString("last_answer",""); if(!s.isEmpty()) speak(s); else addMessage("ARION","Hali eshittiriladigan javob yo‘q.");}
        }).show();
    }

    private void memorySettings() {
        EditText box=new EditText(this);box.setMinLines(5);box.setGravity(Gravity.TOP);box.setHint("Masalan: Men Kitobda yashayman. Menga o‘zbekcha sodda tushuntiring.");box.setText(prefs.getString("long_memory",""));
        new AlertDialog.Builder(this).setTitle("ARION xotirasi").setMessage("Bu yerga eslab qolishini xohlagan ma’lumotlaringizni yozing. Maxfiy ma’lumotlarni kiritmang.").setView(box).setPositiveButton("Saqlash",(d,w)->{prefs.edit().putString("long_memory",box.getText().toString().trim()).apply();addMessage("ARION","Xotira telefoningizda saqlandi.");}).setNeutralButton("Xotirani tozalash",(d,w)->{prefs.edit().remove("long_memory").apply();addMessage("ARION","Saqlangan xotira tozalandi.");}).setNegativeButton("Yopish",null).show();
    }

    private void settings() {
        LinearLayout box=new LinearLayout(this); box.setPadding(dp(16),dp(8),dp(16),dp(8)); box.setOrientation(1);
        box.addView(label("Groq API kaliti (faqat telefoningizda saqlanadi):",14));
        keyInput=new EditText(this); keyInput.setSingleLine(true); keyInput.setHint("gsk_..."); keyInput.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD); keyInput.setText(prefs.getString("api_key","")); box.addView(keyInput);
        new AlertDialog.Builder(this).setTitle("ARION sozlamalari").setView(box).setPositiveButton("Saqlash",(d,w)->{String k=keyInput.getText().toString().trim();prefs.edit().putString("api_key",k).apply();addMessage("ARION",k.isEmpty()?"API kaliti o‘chirildi.":"API kaliti telefonda saqlandi.");}).setNegativeButton("Bekor qilish",null).show();
    }

    private void pickImage() {
        Intent i=new Intent(Intent.ACTION_GET_CONTENT);i.setType("image/*");i.addCategory(Intent.CATEGORY_OPENABLE);
        try{startActivityForResult(Intent.createChooser(i,"Rasm tanlang"),PICK_IMAGE);}catch(Exception e){addMessage("ARION","Rasm tanlash oynasi ochilmadi.");}
    }
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data) {
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode==PICK_IMAGE&&resultCode==RESULT_OK&&data!=null&&data.getData()!=null) {
            try {
                Uri uri=data.getData(); InputStream in=getContentResolver().openInputStream(uri); Bitmap original=BitmapFactory.decodeStream(in); if(in!=null)in.close();
                if(original==null)throw new Exception("Rasm o‘qilmadi");
                int max=1280;float scale=Math.min(1f,(float)max/Math.max(original.getWidth(),original.getHeight()));
                Bitmap resized=Bitmap.createScaledBitmap(original,Math.max(1,(int)(original.getWidth()*scale)),Math.max(1,(int)(original.getHeight()*scale)),true);
                ByteArrayOutputStream out=new ByteArrayOutputStream();resized.compress(Bitmap.CompressFormat.JPEG,78,out);
                selectedImageData=Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP);
                input.setText("Ushbu rasmni tahlil qilib, batafsil tushuntir.");
                Toast.makeText(this,"Rasm biriktirildi. ➤ ni bosing.",Toast.LENGTH_LONG).show();
                if(resized!=original)resized.recycle();original.recycle();
            }catch(Exception e){addMessage("ARION","Rasmni yuklab bo‘lmadi: "+e.getMessage());}
        }
    }

    private void listen() {
        if (android.os.Build.VERSION.SDK_INT >= 23 && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) { requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 71); addMessage("ARION","Mikrofon uchun ruxsat so‘raldi. Ruxsat bergach 🎙 ni yana bosing."); return; }
        if(!SpeechRecognizer.isRecognitionAvailable(this)){addMessage("ARION","Bu telefonda nutqni aniqlash xizmati mavjud emas.");return;}
        SpeechRecognizer sr=SpeechRecognizer.createSpeechRecognizer(this);
        Intent i=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH); i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM); i.putExtra(RecognizerIntent.EXTRA_LANGUAGE,"uz-UZ"); i.putExtra(RecognizerIntent.EXTRA_PROMPT,"ARIONga gapiring");
        sr.setRecognitionListener(new RecognitionListener(){
            public void onReadyForSpeech(Bundle b){runOnUiThread(()->status.setText("Tinglayapman..."));}
            public void onBeginningOfSpeech(){} public void onRmsChanged(float v){} public void onBufferReceived(byte[] b){}
            public void onEndOfSpeech(){runOnUiThread(()->status.setText("Ovoz qayta ishlanmoqda..."));}
            public void onError(int e){runOnUiThread(()->status.setText("Tayyor"));sr.destroy();if(handsFree)handler.postDelayed(()->listen(),1200);}
            public void onResults(Bundle b){ArrayList<String> r=b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);if(r!=null&&!r.isEmpty())runOnUiThread(()->{input.setText(r.get(0));send();});sr.destroy();}
            public void onPartialResults(Bundle b){} public void onEvent(int t,Bundle b){}
        });
        try { sr.startListening(i); } catch(Exception e){sr.destroy();addMessage("ARION","Mikrofonni ishga tushirib bo‘lmadi.");}
    }
    private void speak(String s) { if(ttsReady) tts.speak(s,android.speech.tts.TextToSpeech.QUEUE_FLUSH,null,"arion"); else addMessage("ARION","Telefonning matnni ovozga aylantirish xizmati hali tayyor emas."); }
    private void phoneMenu() {
        String[] items={"Qo‘ng‘iroq qilish","SMS yozish","Kontaktlar","Internetda qidirish"};
        new AlertDialog.Builder(this).setTitle("Telefon funksiyalari").setItems(items,(d,w)->{
            try {
                Intent i;
                if(w==0){i=new Intent(Intent.ACTION_DIAL);i.setData(Uri.parse("tel:"));}
                else if(w==1){i=new Intent(Intent.ACTION_SENDTO);i.setData(Uri.parse("smsto:"));}
                else if(w==2){i=new Intent(Intent.ACTION_VIEW);i.setType("vnd.android.cursor.dir/contact");}
                else {openWeb("https://www.google.com");return;}
                startActivity(i);
            } catch(Exception e){addMessage("ARION","Bu funksiya ochilmadi.");}
        }).show();
    }
    private void openWeb(String url){try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));}catch(Exception e){addMessage("ARION","Brauzer topilmadi.");}}
    private String UriEncode(String s){try{return java.net.URLEncoder.encode(s,"UTF-8");}catch(Exception e){return s;}}
    private double eval(String s) { return new Object(){int p=-1,ch;void next(){ch=(++p<s.length())?s.charAt(p):-1;}boolean eat(int c){while(ch==' ')next();if(ch==c){next();return true;}return false;}double parse(){next();double x=sum();if(p<s.length())throw new RuntimeException();return x;}double sum(){double x=prod();for(;;){if(eat('+'))x+=prod();else if(eat('-'))x-=prod();else return x;}}double prod(){double x=atom();for(;;){if(eat('*'))x*=atom();else if(eat('/'))x/=atom();else return x;}}double atom(){if(eat('+'))return atom();if(eat('-'))return -atom();double x;int start=p;if(eat('(')){x=sum();if(!eat(')'))throw new RuntimeException();}else{while((ch>='0'&&ch<='9')||ch=='.')next();if(start==p)throw new RuntimeException();x=Double.parseDouble(s.substring(start,p));}return x;}}.parse();}
    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] results){super.onRequestPermissionsResult(requestCode,permissions,results);}
    @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);if(tts!=null){tts.stop();tts.shutdown();}super.onDestroy();}
}
