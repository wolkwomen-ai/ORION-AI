package org.orion.ai;

import android.app.*;
import android.os.*;
import android.content.*;
import android.speech.*;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
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
    private boolean ttsReady = false;
    private static final String API = "https://api.groq.com/openai/v1/chat/completions";
    private static final String MODEL = "qwen/qwen3.8-27b";
    private final ArrayList<JSONObject> history = new ArrayList<>();

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("orion_secure_prefs", MODE_PRIVATE);
        tts = new android.speech.tts.TextToSpeech(this, result -> {
            if (result == android.speech.tts.TextToSpeech.SUCCESS) {
                ttsReady = true;
                int r = tts.setLanguage(new Locale("uz", "UZ"));
                if (r == android.speech.tts.TextToSpeech.LANG_MISSING_DATA || r == android.speech.tts.TextToSpeech.LANG_NOT_SUPPORTED)
                    tts.setLanguage(new Locale("ru", "RU"));
            }
        });
        buildUi();
        loadHistory();
        addMessage("ORION", "Assalomu alaykum! Men ORIONman. Suhbatni boshlash uchun Groq API kalitini ⚙ Sozlamalar bo‘limiga kiriting.");
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
        TextView title = label("✦ ORION AI", 23); title.setTypeface(null,1); top.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        top.addView(button("⚙ Sozlamalar", this::settings));
        root.addView(top);
        status = label("Tayyor • Native Android", 12); root.addView(status);
        scroll = new ScrollView(this); messages = new LinearLayout(this); messages.setOrientation(1); scroll.addView(messages);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout actions = new LinearLayout(this); actions.setOrientation(0);
        input = new EditText(this); input.setHint("Savolingizni yozing..."); input.setTextSize(16); input.setMinLines(1); input.setMaxLines(4); input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        actions.addView(input,new LinearLayout.LayoutParams(0,-2,1));
        actions.addView(button("🎙", this::listen));
        actions.addView(button("➤", this::send));
        root.addView(actions);
        LinearLayout bottom = new LinearLayout(this); bottom.setGravity(Gravity.CENTER);
        bottom.addView(button("🔊 Oxirgi javob", () -> { String s=prefs.getString("last_answer",""); if(!s.isEmpty()) speak(s); }));
        bottom.addView(button("🌐 Internet", () -> openWeb("https://www.google.com")));
        bottom.addView(button("📞 Telefon", this::phoneMenu));
        root.addView(bottom);
        setContentView(root);
    }

    private void addMessage(String who, String text) {
        TextView v = label(who + "\n" + text, 15);
        v.setBackgroundColor(who.equals("Siz") ? Color.rgb(222,236,255) : Color.WHITE);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1,-2); p.setMargins(0,dp(5),0,dp(5)); messages.addView(v,p);
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }
    private void send() {
        String q=input.getText().toString().trim(); if(q.isEmpty()) return;
        input.setText(""); ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(input.getWindowToken(),0);
        addMessage("Siz",q);
        if(q.startsWith("/hisob ")) { try { double n=eval(q.substring(7)); addMessage("ORION", "Hisob natijasi: "+n); } catch(Exception e){addMessage("ORION","Hisoblashda xato.");} return; }
        if(q.startsWith("/qidir ")) { openWeb("https://www.google.com/search?q="+UriEncode(q.substring(7))); return; }
        if(q.equals("/vaqt")) { addMessage("ORION",new java.text.SimpleDateFormat("dd.MM.yyyy HH:mm",Locale.getDefault()).format(new Date())); return; }
        String key=prefs.getString("api_key","");
        if(key.isEmpty()){ addMessage("ORION","Avval ⚙ Sozlamalar orqali Groq API kalitini kiriting."); return; }
        status.setText("ORION javob tayyorlamoqda...");
        new Thread(() -> {
            try {
                String answer=ask(q,key);
                runOnUiThread(() -> { status.setText("Tayyor"); addMessage("ORION",answer); prefs.edit().putString("last_answer",answer).apply(); });
            } catch(Exception e) {
                String err=e.getMessage()==null?"Noma’lum xato":e.getMessage();
                runOnUiThread(() -> { status.setText("Xatolik"); addMessage("ORION","Xatolik: "+err); });
            }
        }).start();
    }
    private String ask(String q,String key) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(API).openConnection();
        c.setRequestMethod("POST"); c.setConnectTimeout(20000); c.setReadTimeout(90000); c.setDoOutput(true);
        c.setRequestProperty("Authorization","Bearer "+key); c.setRequestProperty("Content-Type","application/json");
        JSONArray msgs=new JSONArray();
        JSONObject sys=new JSONObject(); sys.put("role","system"); sys.put("content","Sen ORION nomli shaxsiy sun’iy intellekt yordamchisisan. O‘zbek tilida ravon, aniq va foydali javob ber. Bilmagan narsangni to‘qima. Bajarilmagan ishni bajarildi dema. Xavfli telefon amallarini ruxsatsiz bajarma."); msgs.put(sys);
        synchronized(history) { for(JSONObject h:history) msgs.put(h); }
        JSONObject user=new JSONObject(); user.put("role","user"); user.put("content",q); msgs.put(user);
        JSONObject body=new JSONObject(); body.put("model",MODEL); body.put("messages",msgs); body.put("temperature",0.6); body.put("max_tokens",1800);
        try(OutputStream os=c.getOutputStream()){os.write(body.toString().getBytes("UTF-8"));}
        int code=c.getResponseCode(); InputStream is=code>=200&&code<300?c.getInputStream():c.getErrorStream();
        ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] buf=new byte[4096]; int n; while((n=is.read(buf))!=-1) out.write(buf,0,n);
        String raw=new String(out.toByteArray(),"UTF-8"); c.disconnect();
        JSONObject result=new JSONObject(raw);
        if(code<200||code>=300) { JSONObject er=result.optJSONObject("error"); throw new Exception("Groq "+code+": "+(er==null?raw:er.optString("message",raw))); }
        String answer=result.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content","").trim();
        synchronized(history) {
            JSONObject u=new JSONObject(); u.put("role","user"); u.put("content",q); history.add(u);
            JSONObject a=new JSONObject(); a.put("role","assistant"); a.put("content",answer); history.add(a);
            while(history.size()>30) history.remove(0);
            JSONArray arr=new JSONArray(history); prefs.edit().putString("history",arr.toString()).apply();
        }
        return answer.isEmpty()?"ORION bo‘sh javob oldi.":answer;
    }
    private void loadHistory() {
        try { JSONArray a=new JSONArray(prefs.getString("history","[]")); for(int i=0;i<a.length();i++){JSONObject o=a.getJSONObject(i);history.add(o);addMessage(o.optString("role").equals("user")?"Siz":"ORION",o.optString("content"));} } catch(Exception ignored) {}
    }
    private void settings() {
        LinearLayout box=new LinearLayout(this); box.setPadding(dp(16),dp(8),dp(16),dp(8)); box.setOrientation(1);
        box.addView(label("Groq API kaliti (telefoningizda saqlanadi):",14));
        keyInput=new EditText(this); keyInput.setSingleLine(true); keyInput.setHint("gsk_..."); keyInput.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD); keyInput.setText(prefs.getString("api_key","")); box.addView(keyInput);
        new AlertDialog.Builder(this).setTitle("ORION sozlamalari").setView(box).setPositiveButton("Saqlash",(d,w)->{String k=keyInput.getText().toString().trim();prefs.edit().putString("api_key",k).apply();addMessage("ORION",k.isEmpty()?"API kaliti o‘chirildi.":"API kaliti telefonda saqlandi.");}).setNegativeButton("Bekor qilish",null).show();
    }
    private void listen() {
        if (android.os.Build.VERSION.SDK_INT >= 23 && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) { requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 71); addMessage("ORION","Mikrofon uchun ruxsat so‘raldi. Ruxsat bergach 🎙 ni yana bosing."); return; }
        if(!SpeechRecognizer.isRecognitionAvailable(this)){addMessage("ORION","Bu telefonda nutqni aniqlash xizmati mavjud emas.");return;}
        SpeechRecognizer sr=SpeechRecognizer.createSpeechRecognizer(this);
        Intent i=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH); i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM); i.putExtra(RecognizerIntent.EXTRA_LANGUAGE,"uz-UZ"); i.putExtra(RecognizerIntent.EXTRA_PROMPT,"ORIONga gapiring");
        sr.setRecognitionListener(new RecognitionListener(){
            public void onReadyForSpeech(Bundle b){runOnUiThread(()->status.setText("Tinglayapman..."));}
            public void onBeginningOfSpeech(){} public void onRmsChanged(float v){} public void onBufferReceived(byte[] b){}
            public void onEndOfSpeech(){runOnUiThread(()->status.setText("Ovoz qayta ishlanmoqda..."));}
            public void onError(int e){runOnUiThread(()->{status.setText("Tayyor");addMessage("ORION","Ovoz tanilmadi. Mikrofon va nutq xizmatini tekshiring.");});sr.destroy();}
            public void onResults(Bundle b){ArrayList<String> r=b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);if(r!=null&&!r.isEmpty())runOnUiThread(()->{input.setText(r.get(0));send();});sr.destroy();}
            public void onPartialResults(Bundle b){} public void onEvent(int t,Bundle b){}
        });
        try { sr.startListening(i); } catch(Exception e){sr.destroy();addMessage("ORION","Mikrofonni ishga tushirib bo‘lmadi.");}
    }
    private void speak(String s) { if(ttsReady) tts.speak(s,android.speech.tts.TextToSpeech.QUEUE_FLUSH,null,"orion"); else addMessage("ORION","Telefonning matnni ovozga aylantirish xizmati hali tayyor emas."); }
    private void phoneMenu() {
        String[] items={"Qo‘ng‘iroq qilish","SMS yozish","Kontaktlar"};
        new AlertDialog.Builder(this).setTitle("Telefon funksiyalari").setItems(items,(d,w)->{
            try {
                Intent i;
                if(w==0){i=new Intent(Intent.ACTION_DIAL);i.setData(android.net.Uri.parse("tel:"));}
                else if(w==1){i=new Intent(Intent.ACTION_SENDTO);i.setData(android.net.Uri.parse("smsto:"));}
                else {i=new Intent(Intent.ACTION_VIEW);i.setType("vnd.android.cursor.dir/contact");}
                startActivity(i);
            } catch(Exception e){addMessage("ORION","Bu funksiya ochilmadi.");}
        }).show();
    }
    private void openWeb(String url){try{startActivity(new Intent(Intent.ACTION_VIEW,android.net.Uri.parse(url)));}catch(Exception e){addMessage("ORION","Brauzer topilmadi.");}}
    private String UriEncode(String s){try{return java.net.URLEncoder.encode(s,"UTF-8");}catch(Exception e){return s;}}
    private double eval(String s) { return new Object(){int p=-1,ch;void next(){ch=(++p<s.length())?s.charAt(p):-1;}boolean eat(int c){while(ch==' ')next();if(ch==c){next();return true;}return false;}double parse(){next();double x=sum();if(p<s.length())throw new RuntimeException();return x;}double sum(){double x=prod();for(;;){if(eat('+'))x+=prod();else if(eat('-'))x-=prod();else return x;}}double prod(){double x=atom();for(;;){if(eat('*'))x*=atom();else if(eat('/'))x/=atom();else return x;}}double atom(){if(eat('+'))return atom();if(eat('-'))return -atom();double x;int start=p;if(eat('(')){x=sum();if(!eat(')'))throw new RuntimeException();}else{while((ch>='0'&&ch<='9')||ch=='.')next();if(start==p)throw new RuntimeException();x=Double.parseDouble(s.substring(start,p));}return x;}}.parse();}
    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] results){super.onRequestPermissionsResult(requestCode,permissions,results);}
    @Override protected void onDestroy(){if(tts!=null){tts.stop();tts.shutdown();}super.onDestroy();}
}
