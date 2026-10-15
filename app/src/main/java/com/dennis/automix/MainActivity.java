package com.dennis.automix;

import android.app.Activity;
import android.os.Bundle;
import android.os.Environment;
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
import android.widget.ScrollView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import com.dennis.automix.audio.AutoMixEngine;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

/** Minimal dependency-free Android UI for the offline 2–4 track proof of concept. */
public final class MainActivity extends Activity {
    private static final int ADD_WAV=100, IMPORT_ZIP=101, SAVE_WAV=102, SAVE_QC=103, SAVE_ALT=104;
    private static final int BG=Color.rgb(12,16,25), CARD=Color.rgb(26,34,48);
    private static final int ACCENT=Color.rgb(77,219,192), TEXT=Color.WHITE;
    private static final int MUTED=Color.rgb(169,185,202);
    private static final long MAX_FILE=1_300_000_000L, MAX_ZIP=3_000_000_000L;
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private final ArrayList<AutoMixEngine.Track> tracks=new ArrayList<>();
    private LinearLayout entries;
    private TextView status, summary;
    private ProgressBar progress;
    private Button add, zip, clear, run, save, saveAlt, qc;
    private volatile boolean busy;
    private File rendered, alternate, qcFile;

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        LinearLayout root=new LinearLayout(this);
        root.setPadding(dp(18),dp(22),dp(18),dp(30));
        root.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(root);
        TextView title=text("DENNIS",12,ACCENT,true);root.addView(title);
        TextView name=text("AutoMix Studio",29,TEXT,true);root.addView(name);
        TextView subtitle=text("OFFLINE  •  INTELLIGENT WAV MIXING  •  v0.2.0",11,MUTED,false);
        subtitle.setPadding(0,dp(4),0,dp(21));root.addView(subtitle);

        LinearLayout importPanel=panel(root);
        importPanel.addView(text("01  IMPORT YOUR TRACKS",14,TEXT,true));
        add=button("＋  Add WAV files",true);
        zip=button("Import ZIP of WAV stems",false);
        importPanel.addView(add);
        importPanel.addView(zip);
        TextView inputHelp=text("Select 2–4 start-aligned mono or stereo WAVs. A second selection adds to the current set.",12,MUTED,false);
        inputHelp.setPadding(0,dp(7),0,0);importPanel.addView(inputHelp);
        entries=new LinearLayout(this);
        entries.setOrientation(LinearLayout.VERTICAL);
        entries.setPadding(0,dp(12),0,0);
        importPanel.addView(entries);
        clear=button("Clear tracks",false);
        importPanel.addView(clear);

        LinearLayout mixPanel=panel(root);
        mixPanel.addView(text("02  AUTOMATIC MIX",14,TEXT,true));
        TextView mixHelp=text("Two-stem: preserves original balance (+1 dB vocal A/B). Four-stem: analyzes co-active vocal/instrument masking, makes gentle EQ/level changes, and exports a transparent reference.",12,MUTED,false);
        mixHelp.setPadding(0,dp(8),0,dp(8));
        mixPanel.addView(mixHelp);
        run=button("AUTO MIX WAV TRACKS",true);
        mixPanel.addView(run);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);progress.setProgress(0);
        progress.setProgressTintList(ColorStateList.valueOf(ACCENT));
        LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(-1,dp(6));
        pp.topMargin=dp(14);mixPanel.addView(progress,pp);
        status=text("Ready to import WAV stems.",12,MUTED,false);
        status.setPadding(0,dp(9),0,0);
        mixPanel.addView(status);

        LinearLayout exportPanel=panel(root);
        exportPanel.addView(text("03  PREMASTER EXPORT",14,TEXT,true));
        summary=text("Processing: 24-bit stereo WAV with 6 dBFS sample-peak headroom. This is a premaster, not a mastered release.",12,MUTED,false);
        summary.setPadding(0,dp(8),0,dp(7));
        exportPanel.addView(summary);
        save=button("SAVE AUTO MIX PREMASTER",true);
        saveAlt=button("SAVE VOCAL +1 dB COMPARISON",false);
        qc=button("Save mix QC / recipe JSON",false);
        exportPanel.addView(save);exportPanel.addView(saveAlt);exportPanel.addView(qc);
        TextView foot=text("Independent from Dennis AI Mastering Lab. No cloud processing, accounts or file uploads.",11,MUTED,false);
        foot.setPadding(dp(2),dp(12),dp(2),0);root.addView(foot);
        setContentView(scroll);

        add.setOnClickListener(v->selectWavs());
        zip.setOnClickListener(v->selectZip());
        clear.setOnClickListener(v->{
            if(busy)return;
            for(AutoMixEngine.Track t:tracks)t.file.delete();
            tracks.clear();rendered=null;alternate=null;qcFile=null;progress.setProgress(0);
            updateTracks();status.setText("Tracks cleared.");summary.setText("No premaster rendered.");
            updateButtons();
        });
        run.setOnClickListener(v->startMix());
        save.setOnClickListener(v->saveDocument(SAVE_WAV,"audio/wav",
                 "AutoMix_Premaster_24bit.wav"));
        saveAlt.setOnClickListener(v->saveDocument(SAVE_ALT,"audio/wav",
                 alternate!=null?alternate.getName():"AutoMix_Comparison_24bit.wav"));
        qc.setOnClickListener(v->saveDocument(SAVE_QC,"application/json",
                 "AutoMix_Mix_QC.json"));
        updateTracks();updateButtons();
    }
    private void selectWavs(){
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);
        i.putExtra(Intent.EXTRA_MIME_TYPES,
             new String[]{"audio/wav","audio/x-wav","audio/wave","audio/vnd.wave",
                           "application/octet-stream"});
        startActivityForResult(i,ADD_WAV);
    }
    private void selectZip(){
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"application/zip",
                 "application/x-zip-compressed","application/octet-stream"});
        startActivityForResult(i,IMPORT_ZIP);
    }
    private void saveDocument(int request,String mime,String filename){
        File file=request==SAVE_WAV?rendered:request==SAVE_ALT?alternate:qcFile;
        if(busy||file==null||!file.isFile()){notice("No completed output.");return;}
        Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(mime);
        i.putExtra(Intent.EXTRA_TITLE,filename);
        startActivityForResult(i,request);
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(result!=RESULT_OK||data==null||busy)return;
        if(request==ADD_WAV){
            ArrayList<Uri> uris=new ArrayList<>();
            ClipData clip=data.getClipData();
            if(clip!=null){
                for(int i=0;i<clip.getItemCount();i++)uris.add(clip.getItemAt(i).getUri());
            }else if(data.getData()!=null)uris.add(data.getData());
            if(uris.isEmpty())return;
            setBusy(true,"Importing WAV files...");
            executor.execute(()->{
                ArrayList<AutoMixEngine.Track> imported=new ArrayList<>();
                try{
                    if(uris.size()+tracks.size()>4)
                        throw new IOException("Four tracks maximum in this version. Clear tracks first.");
                    for(Uri uri:uris){
                        String filename=getFilename(uri);
                        if(!filename.toLowerCase(Locale.ROOT).endsWith(".wav"))
                            throw new IOException("Only WAV audio is supported: "+filename);
                        File file=copy(uri,filename);
                        try(AutoMixEngine.WavReader unused=new AutoMixEngine.WavReader(file)){}
                        catch(Exception e){file.delete();throw e;}
                        imported.add(new AutoMixEngine.Track(filename,file));
                    }
                    runOnUiThread(()->{
                        tracks.addAll(imported);
                        rendered=null;alternate=null;qcFile=null;
                        updateTracks();setBusy(false,"Imported "+imported.size()+" WAV file(s).");
                    });
                }catch(Exception e){
                    for(AutoMixEngine.Track t:imported)t.file.delete();
                    failed(e);
                }
            });
        }else if(request==IMPORT_ZIP){
            Uri uri=data.getData();if(uri==null)return;
            setBusy(true,"Extracting WAV stems...");
            executor.execute(()->{
                ArrayList<AutoMixEngine.Track> imported=new ArrayList<>();
                try(InputStream raw=getContentResolver().openInputStream(uri)){
                    if(raw==null)throw new IOException("Cannot open ZIP.");
                    long total=0;
                    try(ZipInputStream zis=new ZipInputStream(new BufferedInputStream(raw,64*1024))){
                        ZipEntry entry;
                        while((entry=zis.getNextEntry())!=null){
                            if(entry.isDirectory()||!entry.getName().toLowerCase(Locale.ROOT).endsWith(".wav")){
                                zis.closeEntry();continue;
                            }
                            if(imported.size()+tracks.size()>=4)
                                throw new IOException("ZIP contains more WAVs than the 4-track limit.");
                            String name=new File(entry.getName().replace('\\','/')).getName();
                            File target=freshInput(name);
                            long length=0;
                            try(OutputStream out=new BufferedOutputStream(new FileOutputStream(target),64*1024)){
                                byte[] b=new byte[64*1024];int n;
                                while((n=zis.read(b))!=-1){
                                    length+=n;total+=n;
                                    if(length>MAX_FILE||total>MAX_ZIP)throw new IOException("ZIP audio exceeds safe import size.");
                                    out.write(b,0,n);
                                }
                            }catch(Exception e){target.delete();throw e;}
                            try(AutoMixEngine.WavReader unused=new AutoMixEngine.WavReader(target)){}
                            catch(Exception e){target.delete();throw e;}
                            imported.add(new AutoMixEngine.Track(name,target));
                            zis.closeEntry();
                        }
                    }
                    if(imported.isEmpty())throw new IOException("The ZIP contains no supported WAV files.");
                    runOnUiThread(()->{
                        tracks.addAll(imported);rendered=null;alternate=null;qcFile=null;
                        updateTracks();setBusy(false,"Imported "+imported.size()+" WAVs from ZIP.");
                    });
                }catch(Exception e){
                    for(AutoMixEngine.Track t:imported)t.file.delete();
                    failed(e);
                }
            });
        }else if(request==SAVE_WAV||request==SAVE_QC||request==SAVE_ALT){
            Uri uri=data.getData();if(uri==null)return;
            File file=request==SAVE_WAV?rendered:request==SAVE_ALT?alternate:qcFile;
            setBusy(true,"Saving output...");
            executor.execute(()->{
                try(InputStream in=new BufferedInputStream(new FileInputStream(file),64*1024);
                    OutputStream out=new BufferedOutputStream(getContentResolver().openOutputStream(uri,"w"),64*1024)){
                    if(out==null)throw new IOException("Unable to write selected destination.");
                    byte[] b=new byte[64*1024];int n;
                    while((n=in.read(b))!=-1)out.write(b,0,n);
                    runOnUiThread(()->setBusy(false,"Saved "+file.getName()));
                }catch(Exception e){failed(e);}
            });
        }
    }
    private void startMix(){
        if(busy||tracks.size()<2||tracks.size()>4){notice("Import 2–4 WAV tracks first.");return;}
        ArrayList<AutoMixEngine.Track> input=new ArrayList<>(tracks);
        setBusy(true,"Analysing tracks...");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        executor.execute(()->{
            try{
                File output=new File(outputFolder(),"AutoMix_Premaster_24bit.wav");
                AutoMixEngine.Result result=AutoMixEngine.mix(input,output,
                    (pct,message)->runOnUiThread(()->{
                        progress.setProgress(pct);
                        status.setText(pct+"%  •  "+message);
                    }));
                AutoMixEngine.Result comparison=null;
                File comparisonFile=null;
                if(input.size()==2 && hasNamedVocal(input)){
                    runOnUiThread(()->setBusy(true,"Rendering vocal-focus comparison..."));
                    comparisonFile=new File(outputFolder(),"AutoMix_VocalFocusPlus1dB_24bit.wav");
                    comparison=AutoMixEngine.mix(input,comparisonFile,AutoMixEngine.MixMode.VOCAL_FOCUS,null);
                }else if(input.size()==4){
                    runOnUiThread(()->setBusy(true,"Rendering four-stem transparent reference..."));
                    comparisonFile=new File(outputFolder(),"AutoMix_4Stem_Reference_24bit.wav");
                    comparison=AutoMixEngine.mix(input,comparisonFile,AutoMixEngine.MixMode.STEM_REFERENCE,null);
                }
                final AutoMixEngine.Result compareResult=comparison;
                final File finishedComparison=comparisonFile;
                JSONObject report=makeReport(result,compareResult);
                File qcOutput=new File(outputFolder(),"AutoMix_Mix_QC.json");
                try(Writer writer=new OutputStreamWriter(new FileOutputStream(qcOutput),"UTF-8")){
                    writer.write(report.toString(2));
                }
                runOnUiThread(()->{
                    rendered=output;alternate=finishedComparison;qcFile=qcOutput;
                    save.setText(result.tracks.size()==2?
                        "SAVE ORIGINAL-BALANCE PREMASTER":"SAVE AUTO MIX PREMASTER");
                    saveAlt.setText(result.tracks.size()==4?
                        "SAVE 4-STEM TRANSPARENT REFERENCE":"SAVE VOCAL +1 dB COMPARISON");
                    String note=String.format(Locale.US,
                      "PREMASTER READY\n%d tracks · %d Hz · 24-bit stereo\nPeak %.2f dBFS (sample) · trim %.2f dB\n%s\nNot mastered: A/B compare before mastering.",
                      result.tracks.size(),result.sampleRate,result.peakDbFs,result.outputGainDb,
                      result.mixNote);
                    summary.setText(note);
                    setBusy(false,"Mix complete. Use Save Premaster WAV to export.");
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                });
            }catch(Exception e){
                runOnUiThread(()->getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
                failed(e);
            }
        });
    }
    private static boolean hasNamedVocal(List<AutoMixEngine.Track> input){
        for(AutoMixEngine.Track t:input){
            String n=t.name.toLowerCase(Locale.ROOT);
            if(n.contains("vocal")||n.contains("voice")||n.contains("vox"))return true;
        }
        return false;
    }
    private JSONObject makeReport(AutoMixEngine.Result r,AutoMixEngine.Result focus)throws Exception{
        JSONObject result=new JSONObject();
        result.put("application","Dennis AI AutoMix Studio");
        result.put("version","0.2.0");
        result.put("mix_mode",r.mode.name());
        result.put("two_stem_unity_balance",r.tracks.size()==2);
        result.put("four_stem_roles_verified",r.fourStemRecognized);
        result.put("coactive_seconds",r.coactiveSeconds);
        result.put("mix_plan",r.mixNote);
        result.put("type","mix-only premaster; not mastered");
        result.put("sample_rate",r.sampleRate);
        result.put("frames",r.frames);
        result.put("format","PCM 24-bit stereo WAV");
        result.put("output_peak_dbfs_sample",r.peakDbFs);
        result.put("output_trim_db",r.outputGainDb);
        result.put("original_mix_peak",r.preScalePeak);
        result.put("target_peak_dbfs_sample",-6.0);
        result.put("processing",r.tracks.size()==2?"transparent two-stem sum, no EQ, sample-peak trim only":
            r.tracks.size()==4?"co-active analysis, guarded subtractive EQ and level decisions (if justified), peak trim":
            "legacy three-stem gains and HPF, sample-peak trim");
        if(focus!=null){
            JSONObject ab=new JSONObject();
            ab.put("filename",focus.mode==AutoMixEngine.MixMode.STEM_REFERENCE?
                "AutoMix_4Stem_Reference_24bit.wav":"AutoMix_VocalFocusPlus1dB_24bit.wav");
            ab.put("mode",focus.mode.name());
            ab.put("peak_dbfs_sample",focus.peakDbFs);
            ab.put("output_trim_db",focus.outputGainDb);
            JSONArray gains=new JSONArray();
            for(AutoMixEngine.Analysis a:focus.tracks){
                JSONObject track=new JSONObject();
                track.put("name",a.name);track.put("gain_db",a.gainDb);
                track.put("eq_db",a.eqDb);track.put("eq_hz",a.eqHz);
                gains.put(track);
            }
            ab.put("tracks",gains);
            result.put("ab_candidate",ab);
        }
        JSONArray stems=new JSONArray();
        for(AutoMixEngine.Analysis a:r.tracks){
            JSONObject s=new JSONObject();
            s.put("name",a.name);s.put("role",a.role);
            s.put("peak_linear",a.peak);s.put("active_rms_linear",a.activeRms);
            s.put("gain_db",a.gainDb);s.put("eq_db",a.eqDb);s.put("eq_hz",a.eqHz);
            s.put("frames",a.frames);s.put("channels",a.channels);
            stems.put(s);
        }
        result.put("tracks",stems);
        result.put("note","No true-peak measurement or loudness mastering in v0.2.0. A/B audition should be loudness-matched.");
        return result;
    }
    private String getFilename(Uri uri){
        try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},
                  null,null,null)){
            if(c!=null&&c.moveToFirst()){
                String s=c.getString(0);
                if(s!=null&&!s.isEmpty())return sanitize(s);
            }
        }catch(Exception ignored){}
        return "track.wav";
    }
    private static String sanitize(String s) {
        String name=new File(s.replace('\\','/')).getName();
        name=name.replaceAll("[^a-zA-Z0-9._ -]","_").trim();
        return name.isEmpty()?"track.wav":name;
    }
    private File freshInput(String name)throws IOException {
        File base=inputFolder();
        String suffix=name.toLowerCase(Locale.ROOT).endsWith(".wav")?".wav":".wav";
        String stem=name.toLowerCase(Locale.ROOT).endsWith(".wav")?name.substring(0,name.length()-4):name;
        return File.createTempFile("mix_"+stem.replaceAll("[^a-zA-Z0-9]","_").substring(0,Math.min(24,stem.length()))+"_",suffix,base);
    }
    private File copy(Uri uri,String name)throws IOException {
        File target=freshInput(name);
        try(InputStream in=getContentResolver().openInputStream(uri);
            OutputStream out=new BufferedOutputStream(new FileOutputStream(target),64*1024)){
            if(in==null)throw new IOException("Unable to open "+name);
            byte[] b=new byte[64*1024];long count=0;int n;
            while((n=in.read(b))!=-1){
                count+=n;
                if(count>MAX_FILE)throw new IOException("WAV exceeds 1.3GB import limit: "+name);
                out.write(b,0,n);
            }
        }catch(IOException|RuntimeException e){target.delete();throw e;}
        return target;
    }
    private File inputFolder()throws IOException{
        File f=getExternalFilesDir("imported");
        if(f==null)f=new File(getFilesDir(),"imported");
        if(!f.exists()&&!f.mkdirs())throw new IOException("Cannot create import folder.");
        return f;
    }
    private File outputFolder()throws IOException{
        File f=getExternalFilesDir(Environment.DIRECTORY_MUSIC);
        if(f==null)f=new File(getFilesDir(),"outputs");
        if(!f.exists()&&!f.mkdirs())throw new IOException("Cannot create output folder.");
        return f;
    }
    private void failed(Exception e){
        runOnUiThread(()->{
            setBusy(false,"ERROR: "+e.getMessage());
            Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show();
        });
    }
    private void setBusy(boolean value,String message){
        busy=value;
        status.setText(message);
        updateButtons();
    }
    private void updateButtons(){
        add.setEnabled(!busy&&tracks.size()<4);
        zip.setEnabled(!busy&&tracks.size()<4);
        clear.setEnabled(!busy&&!tracks.isEmpty());
        run.setEnabled(!busy&&tracks.size()>=2);
        save.setEnabled(!busy&&rendered!=null&&rendered.isFile());
        saveAlt.setEnabled(!busy&&alternate!=null&&alternate.isFile());
        qc.setEnabled(!busy&&qcFile!=null&&qcFile.isFile());
        for(Button b:new Button[]{add,zip,clear,run,save,saveAlt,qc})
            b.setAlpha(b.isEnabled()?1f:.4f);
    }
    private void updateTracks(){
        entries.removeAllViews();
        if(tracks.isEmpty()){
            entries.addView(text("No tracks imported",13,MUTED,false));
            return;
        }
        for(int i=0;i<tracks.size();i++){
            AutoMixEngine.Track t=tracks.get(i);
            TextView item=text(String.format(Locale.US,"%02d  %s  (%.1f MB)",
                i+1,t.name,t.file.length()/1000000.0),12,TEXT,false);
            item.setPadding(dp(9),dp(9),dp(4),dp(9));
            entries.addView(item);
        }
    }
    private LinearLayout panel(LinearLayout parent){
        LinearLayout panel=new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(15),dp(15),dp(15),dp(17));
        GradientDrawable bg=new GradientDrawable();
        bg.setColor(CARD);bg.setCornerRadius(dp(15));
        bg.setStroke(dp(1),Color.rgb(44,56,72));
        panel.setBackground(bg);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);
        p.bottomMargin=dp(14);parent.addView(panel,p);return panel;
    }
    private Button button(String label,boolean primary){
        Button b=new Button(this);
        b.setText(label);
        b.setTextColor(primary?BG:TEXT);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        GradientDrawable bg=new GradientDrawable();
        bg.setColor(primary?ACCENT:Color.rgb(40,52,70));
        bg.setCornerRadius(dp(10));
        b.setBackground(bg);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(49));
        p.topMargin=dp(9);b.setLayoutParams(p);return b;
    }
    private TextView text(String value,int size,int color,boolean bold){
        TextView t=new TextView(this);
        t.setText(value);t.setTextSize(size);t.setTextColor(color);
        if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        return t;
    }
    private int dp(float amount){return Math.round(amount*getResources().getDisplayMetrics().density);}
    private void notice(String msg){Toast.makeText(this,msg,Toast.LENGTH_SHORT).show();}
    @Override protected void onDestroy(){
        // In v0.1, keep the app foregrounded for long renders.
        super.onDestroy();
        executor.shutdownNow();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
}
