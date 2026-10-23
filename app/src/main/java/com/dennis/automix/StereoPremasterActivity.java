package com.dennis.automix;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.content.ClipData;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.dennis.automix.audio.StereoPremasterEngine;
import com.dennis.automix.audio.AutoMixEngine;
import java.io.*;
import java.util.concurrent.*;

public final class StereoPremasterActivity extends Activity {
    private static final int PICK=410, SAVE_PREMASTER=411, SAVE_REFERENCE=412, SAVE_REPORT=413;
    private static final int BG=Color.rgb(12,16,25), CARD=Color.rgb(26,34,48),
        ACCENT=Color.rgb(77,219,192), MUTED=Color.rgb(169,185,202);
    private static final long MAX_INPUT=1_300_000_000L;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private TextView status,details;private ProgressBar progress;
    private Button choose,prepare,save,reference,qc;
    private File source,finished,reportFile;
    private boolean busy;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        ScrollView sc=new ScrollView(this);
        sc.setBackgroundColor(BG);
        LinearLayout body=new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(18),dp(22),dp(18),dp(28));
        sc.addView(body);

        body.addView(text("DENNIS AUTO MIX STUDIO",12,ACCENT,true));
        body.addView(text("Stereo Premaster",29,Color.WHITE,true));
        body.addView(text("v0.2.4 experimental  •  Offline  •  Single WAV",12,MUTED,false));
        body.addView(text("Import a finished stereo mix. The existing 2–12 stem mixer is unaffected.",13,MUTED,false));

        LinearLayout card=panel(body);
        card.addView(text("01  CHOOSE ORIGINAL WAV",14,Color.WHITE,true));
        choose=button("SELECT STEREO WAV",true);card.addView(choose);
        status=text("No WAV selected.",12,MUTED,false);card.addView(status);

        card=panel(body);
        card.addView(text("02  CONSERVATIVE PREMASTER",14,Color.WHITE,true));
        card.addView(text("Preserves the original file and musical dynamics. Never boosts loudness; "+
            "uses only shared sample-peak headroom trim when needed, removes material DC offset, "+
            "and allows a maximum 0.5 dB low-mid cut only with strong evidence.",12,MUTED,false));
        prepare=button("ANALYSE + PREPARE 24-BIT WAV",true);card.addView(prepare);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);progress.setProgress(0);
        progress.setProgressTintList(ColorStateList.valueOf(ACCENT));
        card.addView(progress,new LinearLayout.LayoutParams(-1,dp(8)));

        card=panel(body);
        card.addView(text("03  REVIEW & EXPORT",14,Color.WHITE,true));
        details=text("A processing report will show stereo correlation, mono compatibility, "+
            "dynamics, sample peak and exactly what changed.",12,MUTED,false);card.addView(details);
        save=button("SAVE STEREO PREMASTER",true);card.addView(save);
        reference=button("SAVE UNTOUCHED ORIGINAL A/B",false);card.addView(reference);
        qc=button("SAVE QC REPORT (JSON)",false);card.addView(qc);
        body.addView(text("Exported WAV: stereo 24-bit at source sample rate. This is NOT mastering. "+
            "No limiter, compression, stereo width effect, LUFS targeting or true-peak guarantee. "+
            "Audition the premaster against your original at equal listening loudness before mastering.",
            12,MUTED,false));
        setContentView(sc);
        choose.setOnClickListener(v->pick());
        prepare.setOnClickListener(v->process());
        save.setOnClickListener(v->exportFile(SAVE_PREMASTER));
        reference.setOnClickListener(v->exportFile(SAVE_REFERENCE));
        qc.setOnClickListener(v->exportFile(SAVE_REPORT));
        update();
    }
    private int dp(int x){return (int)(x*getResources().getDisplayMetrics().density+0.5f);}
    private TextView text(String s,int size,int color,boolean bold) {
        TextView t=new TextView(this);t.setText(s);t.setTextColor(color);
        t.setTextSize(size);t.setPadding(0,dp(5),0,dp(7));
        if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        return t;
    }
    private LinearLayout panel(LinearLayout parent) {
        LinearLayout p=new LinearLayout(this);p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(dp(14),dp(12),dp(14),dp(12));
        GradientDrawable background=new GradientDrawable();
        background.setColor(CARD);background.setCornerRadius(dp(15));
        p.setBackground(background);
        LinearLayout.LayoutParams a=new LinearLayout.LayoutParams(-1,-2);
        a.setMargins(0,dp(12),0,dp(3));parent.addView(p,a);return p;
    }
    private Button button(String s,boolean primary) {
        Button b=new Button(this);b.setAllCaps(false);b.setText(s);
        b.setTextColor(primary?Color.rgb(10,24,26):Color.WHITE);
        b.setTextSize(12);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        GradientDrawable background=new GradientDrawable();
        background.setColor(primary?ACCENT:Color.rgb(45,60,77));
        background.setCornerRadius(dp(12));b.setBackground(background);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(48));
        p.setMargins(0,dp(7),0,dp(3));b.setLayoutParams(p);return b;
    }
    private void update(){
        choose.setEnabled(!busy);prepare.setEnabled(!busy&&source!=null&&source.isFile());
        save.setEnabled(!busy&&finished!=null&&finished.isFile());
        reference.setEnabled(!busy&&source!=null&&source.isFile());
        qc.setEnabled(!busy&&reportFile!=null&&reportFile.isFile());
    }
    private File folder()throws IOException {
        File dir=new File(getFilesDir(),"stereo-premaster");
        if(!dir.exists()&&!dir.mkdirs())throw new IOException("Cannot make private work folder");
        return dir;
    }
    private void busy(boolean b,String msg){busy=b;status.setText(msg);update();}
    private void fail(Exception ex){
        runOnUiThread(()->{busy(false,"Problem: "+ex.getMessage());
            Toast.makeText(this,ex.getMessage(),Toast.LENGTH_LONG).show();});
    }
    private void pick(){
        if(busy)return;
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"audio/wav","audio/x-wav",
            "audio/wave","audio/vnd.wave","application/octet-stream"});
        startActivityForResult(i,PICK);
    }
    private void exportFile(int kind){
        File f=kind==SAVE_PREMASTER?finished:kind==SAVE_REFERENCE?source:reportFile;
        if(busy||f==null||!f.isFile())return;
        Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(kind==SAVE_REPORT?"application/json":"audio/wav");
        i.putExtra(Intent.EXTRA_TITLE,kind==SAVE_REPORT?"AutoMix_Stereo_Premaster_QC.json":
            kind==SAVE_REFERENCE?"AutoMix_Original_Reference.wav":
            "AutoMix_Stereo_Premaster_24bit.wav");
        startActivityForResult(i,kind);
    }
    @Override protected void onActivityResult(int req,int result,Intent data){
        super.onActivityResult(req,result,data);
        if(result!=RESULT_OK||data==null||busy)return;
        Uri uri=data.getData();
        if(uri==null)return;
        if(req==PICK){
            busy(true,"Importing original WAV safely...");
            worker.execute(()->{
                File temp=null;
                try{
                    File f=folder();temp=new File(f,"source.incomplete");
                    try(InputStream in=getContentResolver().openInputStream(uri);
                        OutputStream out=new BufferedOutputStream(new FileOutputStream(temp),65536)){
                        if(in==null)throw new IOException("Cannot open selected WAV");
                        byte[] block=new byte[65536];int n;long size=0;
                        while((n=in.read(block))!=-1){
                            if(Thread.currentThread().isInterrupted())throw new IOException("Import cancelled");
                            size+=n;if(size>MAX_INPUT)throw new IOException("WAV exceeds 1.3 GB import limit");
                            out.write(block,0,n);
                        }
                    }
                    try(AutoMixEngine.WavReader r=new AutoMixEngine.WavReader(temp)){
                        if(r.channels!=2||r.frames<1)throw new IOException("Two-channel stereo WAV required");
                    }
                    File original=new File(f,"AutoMix_Original_Reference.wav");
                    if(!temp.renameTo(original))throw new IOException("Cannot finish WAV import");
                    final File selected=original;
                    runOnUiThread(()->{
                        source=selected;finished=null;reportFile=null;progress.setProgress(0);
                        details.setText("Original imported. Choose ANALYSE + PREPARE to create the comparison.");
                        busy(false,"Original stereo WAV ready. Your source outside the app was not modified.");
                    });
                }catch(Exception ex){fail(ex);}
                finally{if(temp!=null)temp.delete();}
            });
        }else if(req==SAVE_PREMASTER||req==SAVE_REFERENCE||req==SAVE_REPORT){
            File local=req==SAVE_PREMASTER?finished:req==SAVE_REFERENCE?source:reportFile;
            if(local==null||!local.isFile())return;
            busy(true,"Saving output...");
            worker.execute(()->{
                try(InputStream in=new BufferedInputStream(new FileInputStream(local),65536);
                    OutputStream out=new BufferedOutputStream(getContentResolver().openOutputStream(uri,"w"),65536)){
                    if(out==null)throw new IOException("Cannot open save destination");
                    byte[] bytes=new byte[65536];int count;
                    while((count=in.read(bytes))!=-1){out.write(bytes,0,count);}
                    runOnUiThread(()->busy(false,"Exported "+local.getName()));
                }catch(Exception ex){fail(ex);}
            });
        }
    }
    private void process(){
        if(busy||source==null||!source.isFile())return;
        busy(true,"Analysing original WAV...");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        final File original=source;
        worker.execute(()->{
            try{
                File output=new File(folder(),"AutoMix_Stereo_Premaster_24bit.wav");
                StereoPremasterEngine.Report r=StereoPremasterEngine.prepare(
                    original,output,(p,message)->runOnUiThread(()->{
                        progress.setProgress(p);status.setText(p+"%  •  "+message);
                    }));
                File report=new File(folder(),"Stereo_Premaster_QC.json");
                try(Writer w=new OutputStreamWriter(new FileOutputStream(report),"UTF-8")){
                    w.write(r.json());
                }
                runOnUiThread(()->{
                    finished=output;reportFile=report;progress.setProgress(100);
                    details.setText(r.summary());
                    busy(false,"Premaster ready. Review the QC and compare with your original.");
                });
            }catch(Exception ex){fail(ex);}
            finally{runOnUiThread(()->getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));}
        });
    }
    @Override public void onDestroy(){worker.shutdownNow();super.onDestroy();}
}
