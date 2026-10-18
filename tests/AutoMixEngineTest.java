import com.dennis.automix.audio.AutoMixEngine;
import java.io.*;
import java.nio.file.*;
import java.util.*;
public final class AutoMixEngineTest {
    private static void verify(boolean value,String msg){if(!value)throw new AssertionError(msg);}
    private static File tone(Path directory,String name,double frequency,double amplitude,int channels,int rate,int frames) throws Exception {
        File f=directory.resolve(name).toFile();
        // Generate input as 24-bit PCM using the same independently readable writer.
        try(AutoMixEngine.WavWriter w=new AutoMixEngine.WavWriter(f,rate,frames)){
            float[] b=new float[4096*2];
            for(int start=0;start<frames;start+=4096){
                int len=Math.min(4096,frames-start);
                for(int i=0;i<len;i++){
                    float sample=(float)(amplitude*Math.sin(2*Math.PI*frequency*(start+i)/rate));
                    b[2*i]=sample;
                    b[2*i+1]=sample*0.7f;
                }
                w.write(b,len);
            }
        }
        return f;
    }
    public static void main(String[] args)throws Exception {
        Path tmp=Files.createTempDirectory("automix");
        final int rate=48000, frames=48000;
        File vocal=tone(tmp,"Lead_Vocals.wav",220,.23,2,rate,frames);
        File drums=tone(tmp,"Drums.wav",95,.18,2,rate,frames);
        File bass=tone(tmp,"Bass.wav",70,.32,2,rate,frames);
        File other=tone(tmp,"Other.wav",440,.15,2,rate,frames);
        File output=tmp.resolve("premaster.wav").toFile();
        AutoMixEngine.Result r=AutoMixEngine.mix(Arrays.asList(
                new AutoMixEngine.Track(vocal.getName(),vocal),
                new AutoMixEngine.Track(drums.getName(),drums),
                new AutoMixEngine.Track(bass.getName(),bass),
                new AutoMixEngine.Track(other.getName(),other)),output,(p,s)->{});
        verify(r.sampleRate==rate && r.frames==frames,"Output properties");
        verify(r.tracks.size()==4,"Track count");
        verify(r.peakDbFs<=-5.99,"Output peak ceiling");
        for(AutoMixEngine.Analysis a:r.tracks)
            verify(a.gainDb>=-4.001 && a.gainDb<=4.001,"Gain safety");
        try(AutoMixEngine.WavReader w=new AutoMixEngine.WavReader(output)){
            verify(w.bits==24 && w.channels==2 && w.frames==frames,"Output is 24-bit stereo");
            float[] b=new float[8192];
            verify(w.read(b,4096)>0,"Readable data");
            verify(Math.abs(b[0]-b[1])<0.02,"Stereo channel relationship");
        }
        // Original-balance two-stem render must have unity track gains, a separate
        // vocal-focus candidate must change only vocal gain by +1 dB.
        List<AutoMixEngine.Track> duo=Arrays.asList(
            new AutoMixEngine.Track("Vocals",vocal),
            new AutoMixEngine.Track("Instrumental",drums));
        File transparent=tmp.resolve("transparent.wav").toFile();
        File focus=tmp.resolve("focus.wav").toFile();
        AutoMixEngine.Result ref=AutoMixEngine.mix(duo,transparent,null);
        AutoMixEngine.Result candidate=AutoMixEngine.mix(duo,focus,AutoMixEngine.MixMode.VOCAL_FOCUS,null);
        verify(Math.abs(ref.tracks.get(0).gainDb)<1e-10&&Math.abs(ref.tracks.get(1).gainDb)<1e-10,
                "Auto 2-stem must preserve unity gains");
        verify(Math.abs(candidate.tracks.get(0).gainDb-1.0)<1e-10
               && Math.abs(candidate.tracks.get(1).gainDb)<1e-10,
                "Vocal A/B candidate must only add 1 dB");
        try(AutoMixEngine.WavReader rw=new AutoMixEngine.WavReader(transparent);
            AutoMixEngine.WavReader rv=new AutoMixEngine.WavReader(vocal);
            AutoMixEngine.WavReader ri=new AutoMixEngine.WavReader(drums)){
            float[] a=new float[8192],b=new float[8192],o=new float[8192];
            rw.read(o,4096);rv.read(a,4096);ri.read(b,4096);
            double scale=Math.pow(10.0,ref.outputGainDb/20.0);
            for(int i=20;i<7000;i++)
                verify(Math.abs(o[i]-(a[i]+b[i])*scale)<0.000002,
                       "Transparent sum must match originals up to common trim");
        }
        boolean rejected=false;
        try{
            AutoMixEngine.mix(Arrays.asList(
                new AutoMixEngine.Track("A",vocal),
                new AutoMixEngine.Track("B",tone(tmp,"short.wav",200,.2,2,rate,rate/2))),
                tmp.resolve("should_fail.wav").toFile(),null);
        }catch(IOException expected){rejected=true;}
        verify(rejected,"Mismatch must be rejected");
        verify(!Files.exists(tmp.resolve("should_fail.wav")),"Failure cleanup");
        long before=output.length();
        boolean refused=false;
        try{
            AutoMixEngine.mix(Arrays.asList(
                new AutoMixEngine.Track("A",vocal),
                new AutoMixEngine.Track("B",tmp.resolve("missing.wav").toFile())),output,null);
        }catch(IOException expected){refused=true;}
        verify(refused && output.length()==before,"Failed mix preserves previous valid master");

        File mono16=tmp.resolve("Vocals_mono16.wav").toFile();
        makeWav(mono16,1,16,1,rate,frames,false);
        File float32=tmp.resolve("Backing_float.wav").toFile();
        makeWav(float32,2,32,3,rate,frames,false);
        try(AutoMixEngine.WavReader r16=new AutoMixEngine.WavReader(mono16);
            AutoMixEngine.WavReader r32=new AutoMixEngine.WavReader(float32)){
            verify(r16.channels==1&&r16.bits==16,"Mono 16-bit input");
            verify(r32.format==3&&r32.bits==32,"Float32 input");
        }
        File mixed=tmp.resolve("mix_formats.wav").toFile();
        AutoMixEngine.Result result=AutoMixEngine.mix(Arrays.asList(
           new AutoMixEngine.Track("Vocals",mono16),
           new AutoMixEngine.Track("Backing",float32)),mixed,null);
        verify(result.peakDbFs<=-5.99 && result.tracks.size()==2,"Mixed PCM16 / float32 input");

        File notFinite=tmp.resolve("NaN_float.wav").toFile();
        makeWav(notFinite,2,32,3,rate,frames,true);
        boolean rejectedNonFinite=false;
        try{
            AutoMixEngine.mix(Arrays.asList(
                 new AutoMixEngine.Track("Vocals",mono16),
                 new AutoMixEngine.Track("Other",notFinite)),
                 tmp.resolve("bad_float_output.wav").toFile(),null);
        }catch(IOException expected){rejectedNonFinite=true;}
        verify(rejectedNonFinite,"Must reject NaN samples");

        // Four-stem planner: overlap-driven conservative processing and
        // a completely transparent four-stem reference candidate.
        File v4=tone(tmp,"Lead_Vocals_Masking.wav",2500,.09,2,rate,frames);
        File d4=tone(tmp,"Drums_Masking.wav",95,.14,2,rate,frames);
        File b4=tone(tmp,"Bass_Masking.wav",75,.16,2,rate,frames);
        File o4=tone(tmp,"Other_Masking.wav",2500,.24,2,rate,frames);
        List<AutoMixEngine.Track> four=Arrays.asList(
            new AutoMixEngine.Track("Vocals",v4),
            new AutoMixEngine.Track("Drums",d4),
            new AutoMixEngine.Track("Bass",b4),
            new AutoMixEngine.Track("Other",o4));
        AutoMixEngine.Result intelligent=AutoMixEngine.mix(
            four,tmp.resolve("intelligent.wav").toFile(),null);
        AutoMixEngine.Result baseFour=AutoMixEngine.mix(
            four,tmp.resolve("reference4.wav").toFile(),AutoMixEngine.MixMode.STEM_REFERENCE,null);
        verify(intelligent.fourStemRecognized,"Must recognize four unique roles");
        verify(intelligent.coactiveSeconds>.9,"Simultaneous vocal/other detection");
        verify(intelligent.tracks.get(3).gainDb<-.29 &&
               intelligent.tracks.get(3).gainDb>=-.91,"Other level bounded cut");
        verify(intelligent.tracks.get(3).eqDb<-.70 &&
               intelligent.tracks.get(3).eqDb>=-.76,"2.5kHz evidence-based EQ engaged");
        verify(baseFour.tracks.get(3).gainDb==0 &&
               baseFour.tracks.get(3).eqDb==0,"Four-stem A/B preserves source settings");
        verify(baseFour.mode==AutoMixEngine.MixMode.STEM_REFERENCE,
               "Four-stem reference mode");
        verify(intelligent.peakDbFs<=-5.99 && baseFour.peakDbFs<=-5.99,
               "Both four-stem paths preserve peak ceiling");
        try(AutoMixEngine.WavReader rw=new AutoMixEngine.WavReader(baseFour.output);
            AutoMixEngine.WavReader r0=new AutoMixEngine.WavReader(v4);
            AutoMixEngine.WavReader r1=new AutoMixEngine.WavReader(d4);
            AutoMixEngine.WavReader r2=new AutoMixEngine.WavReader(b4);
            AutoMixEngine.WavReader r3=new AutoMixEngine.WavReader(o4)){
            float[] dest=new float[8192],p0=new float[8192],p1=new float[8192],
                    p2=new float[8192],p3=new float[8192];
            rw.read(dest,4096);r0.read(p0,4096);r1.read(p1,4096);
            r2.read(p2,4096);r3.read(p3,4096);
            double scale=Math.pow(10.0,baseFour.outputGainDb/20.0);
            for(int i=10;i<6000;i++)verify(
                Math.abs(dest[i]-(p0[i]+p1[i]+p2[i]+p3[i])*scale)<0.000002,
                "Four-stem reference must be transparent");
        }
        List<AutoMixEngine.Track> unnamed=Arrays.asList(
            new AutoMixEngine.Track("A.wav",v4),
            new AutoMixEngine.Track("B.wav",d4),
            new AutoMixEngine.Track("C.wav",b4),
            new AutoMixEngine.Track("D.wav",o4));
        AutoMixEngine.Result unlabeled=AutoMixEngine.mix(
            unnamed,tmp.resolve("unnamed.wav").toFile(),null);
        verify(!unlabeled.fourStemRecognized,"Ambiguous roles must not trigger IQ processing");
        for(AutoMixEngine.Analysis a:unlabeled.tracks)
            verify(a.gainDb==0&&a.eqDb==0,"Ambiguous four-stem output must be unity");
        File quietOther=tone(tmp,"Other_Quiet.wav",2500,.005,2,rate,frames);
        List<AutoMixEngine.Track> quiet=Arrays.asList(
           new AutoMixEngine.Track("Vocals",v4),
           new AutoMixEngine.Track("Drums",d4),
           new AutoMixEngine.Track("Bass",b4),
           new AutoMixEngine.Track("Other",quietOther));
        AutoMixEngine.Result guarded=AutoMixEngine.mix(
             quiet,tmp.resolve("guarded.wav").toFile(),null);
        verify(guarded.tracks.get(3).eqDb==0 && guarded.tracks.get(3).gainDb==0,
               "Quiet non-masking other stem must not receive EQ");
        // Multi-stem capacity regression: 5, 8 and 12 aligned independent WAV inputs.
        // The 5–12 stem extension must not infer independent channel gains from
        // whole-song average RMS; its initial quality-safe mix is a transparent sum.
        List<AutoMixEngine.Track> twelve=new ArrayList<>();
        for(int i=0;i<12;i++){
            File one=tone(tmp,String.format(Locale.US,"Stem_%02d.wav",i),
                          110+i*37,.010+i*.002,2,rate,frames);
            twelve.add(new AutoMixEngine.Track(one.getName(),one));
        }
        for(int trackCount:new int[]{5,8,12}){
            List<AutoMixEngine.Track> many=twelve.subList(0,trackCount);
            File out=tmp.resolve("multistem_"+trackCount+".wav").toFile();
            AutoMixEngine.Result mult=AutoMixEngine.mix(many,out,null);
            verify(mult.tracks.size()==trackCount,"All multi-stem inputs must appear in QC");
            verify(mult.sampleRate==rate&&mult.frames==frames,"Multi-stem duration/sample rate");
            verify(mult.peakDbFs<=-5.99,"Multi-stem safe -6dBFS sample peak ceiling");
            verify(mult.mixNote.contains("transparent mix"),"Truthful multi-stem strategy note");
            for(AutoMixEngine.Analysis a:mult.tracks){
                verify(a.gainDb==0&&a.eqDb==0,
                       "Multi-stem preservation must not modify individual tracks");
            }
            try(AutoMixEngine.WavReader mixReader=new AutoMixEngine.WavReader(out)){
                List<AutoMixEngine.WavReader> sources=new ArrayList<>();
                try{
                    for(AutoMixEngine.Track t:many)sources.add(new AutoMixEngine.WavReader(t.file));
                    float[] observed=new float[4096*2];
                    float[][] originals=new float[trackCount][4096*2];
                    verify(mixReader.read(observed,4096)==4096,"Complete multi-stem first block");
                    for(int i=0;i<trackCount;i++)
                        verify(sources.get(i).read(originals[i],4096)==4096,"Complete source first block");
                    double commonScale=Math.pow(10,mult.outputGainDb/20.0);
                    for(int i=8;i<7000;i++){
                        double expected=0;
                        for(float[] stem:originals)expected+=stem[i];
                        verify(Math.abs(observed[i]-expected*commonScale)<0.000003,
                               "Multi-stem PCM must equal unity summation plus shared trim");
                    }
                }finally{for(AutoMixEngine.WavReader source:sources)source.close();}
            }
        }
        boolean tooManyRejected=false;
        try{
            List<AutoMixEngine.Track> thirteen=new ArrayList<>(twelve);
            thirteen.add(twelve.get(0));
            AutoMixEngine.mix(thirteen,tmp.resolve("thirteen.wav").toFile(),null);
        }catch(IOException expected){tooManyRejected=true;}
        verify(tooManyRejected,"Thirteen tracks must be rejected");
        verify(!Files.exists(tmp.resolve("thirteen.wav")),"Rejected thirteen-track job leaves no WAV");
        // A missing stem must not overwrite a prior successfully mixed 12-stem premaster.
        File good12=tmp.resolve("multistem_12.wav").toFile();
        long preservedBytes=good12.length();
        List<AutoMixEngine.Track> missingStem=new ArrayList<>(twelve);
        missingStem.set(4,new AutoMixEngine.Track("Missing",tmp.resolve("no_such_stem.wav").toFile()));
        boolean failed12=false;
        try{AutoMixEngine.mix(missingStem,good12,null);}
        catch(IOException expected){failed12=true;}
        verify(failed12&&good12.length()==preservedBytes,
               "Failed twelve-track render must preserve the last valid WAV");
        // v0.2.3: genuinely coactive guitar, keyboard and synth masking the lead
        // must result in no more than TWO gentle corrections, leaving bass,
        // drums, both vocal tracks, and ambiguous "Other" stems untouched.
        final int multiFrames=rate*5;
        List<AutoMixEngine.Track> group=new ArrayList<>();
        String[] names={"0 Lead Vocals.wav","1 Backing Vocals.wav","2 Drums.wav",
                        "3 Bass.wav","4 Guitar.wav","5 Keyboard.wav",
                        "6 Synth.wav","7 Other.wav"};
        double[] freq={2500,350,110,70,2500,2500,2500,440};
        double[] amplitude={.06,.025,.10,.15,.27,.24,.22,.05};
        for(int i=0;i<names.length;i++){
            File f=tone(tmp,names[i],freq[i],amplitude[i],2,rate,multiFrames);
            group.add(new AutoMixEngine.Track(names[i],f));
        }
        AutoMixEngine.Result proposed=AutoMixEngine.mix(group,
            tmp.resolve("instrument_aware.wav").toFile(),null);
        AutoMixEngine.Result untouched=AutoMixEngine.mix(group,
            tmp.resolve("instrument_reference.wav").toFile(),
            AutoMixEngine.MixMode.STEM_REFERENCE,null);
        verify(proposed.multiStemRecognized && proposed.multiStemAdjusted,
               "Evidence-based multi-stem mode should detect strong coactivity");
        verify(untouched.mode==AutoMixEngine.MixMode.STEM_REFERENCE,
               "Multi-stem baseline must be a separate reference");
        int corrections=0;
        for(int i=0;i<proposed.tracks.size();i++){
            AutoMixEngine.Analysis a=proposed.tracks.get(i);
            AutoMixEngine.Analysis b=untouched.tracks.get(i);
            verify(b.gainDb==0&&b.eqDb==0,"Reference may not change input levels/tone");
            verify(a.gainDb>=-.40001 && a.gainDb<=.00001,
                   "Instrument gain must remain in narrow subtractive range");
            verify(a.eqDb>=-.60001 && a.eqDb<=.00001,
                   "Instrument EQ must remain in narrow subtractive range");
            if(a.gainDb<-.0001||a.eqDb<-.0001){
                corrections++;
                verify(i>=4 && i<=6,"Only clearly named musical instruments are eligible");
                verify(proposed.multiStemOverlapSeconds[i]>=3,
                       "Instrument adjustment must have strong coactive evidence");
            }
        }
        verify(corrections>0 && corrections<=2,
               "No more than two instruments receive adjustments");
        verify(proposed.peakDbFs<=-5.99 && untouched.peakDbFs<=-5.99,
               "Reference and processed premaster must both have safe sample peaks");
        // Confirm the 8-stem reference is an exact unity sum except common
        // peak trim (24-bit PCM roundoff tolerance).
        try(AutoMixEngine.WavReader w=new AutoMixEngine.WavReader(untouched.output)){
            float[] resultSamples=new float[4096*2];
            verify(w.read(resultSamples,4096)==4096,"Reference WAV readable");
            List<AutoMixEngine.WavReader> sourceReaders=new ArrayList<>();
            try {
                for(AutoMixEngine.Track t:group)sourceReaders.add(new AutoMixEngine.WavReader(t.file));
                float[][] originalSamples=new float[group.size()][4096*2];
                for(int i=0;i<group.size();i++)sourceReaders.get(i).read(originalSamples[i],4096);
                double g=Math.pow(10.0,untouched.outputGainDb/20.0);
                for(int i=16;i<6800;i++){
                    double sum=0;
                    for(float[] sample:originalSamples)sum+=sample[i];
                    verify(Math.abs(resultSamples[i]-g*sum)<.000003,
                           "Unmodified 8-stem reference must equal source sum");
                }
            } finally {for(AutoMixEngine.WavReader w0:sourceReaders)w0.close();}
        }
        // Ambiguous leads must never result in speculative instrument changes.
        List<AutoMixEngine.Track> noClearLead=new ArrayList<>(group);
        noClearLead.set(0,new AutoMixEngine.Track("0 Vocals A.wav",group.get(0).file));
        noClearLead.set(1,new AutoMixEngine.Track("1 Vocals B.wav",group.get(1).file));
        AutoMixEngine.Result safeFallback=AutoMixEngine.mix(noClearLead,
            tmp.resolve("ambiguous_multi.wav").toFile(),null);
        verify(!safeFallback.multiStemAdjusted,
               "Ambiguous lead vocals must leave all stems untouched");
        for(AutoMixEngine.Analysis a:safeFallback.tracks)
            verify(a.gainDb==0 && a.eqDb==0,
                   "Ambiguous multi-stem mix must remain transparent");
        boolean invalidReferenceCount=false;
        try{AutoMixEngine.mix(group.subList(0,3),tmp.resolve("invalid3ref.wav").toFile(),
            AutoMixEngine.MixMode.STEM_REFERENCE,null);}
        catch(IOException expected){invalidReferenceCount=true;}
        verify(invalidReferenceCount,"Reference mode refuses fewer than 4 stems");
        System.out.println("PASS: v0.2.3 guarded 8-stem coactive decisions, max-two bounded cuts, safe fallback and transparent reference");
        System.out.println("PASS: WAV IO, two-stem continuity, four-stem coactivity and guarded EQ, reference transparency, peak-safety, invalid roles, and safe 5/8/12-stem streaming");
    }
    private static void writeU16(DataOutputStream s,int v)throws IOException {
        s.writeByte(v&255);s.writeByte((v>>8)&255);
    }
    private static void writeU32(DataOutputStream s,long v)throws IOException {
        for(int i=0;i<4;i++)s.writeByte((int)(v>>(i*8))&255);
    }
    private static void makeWav(File file,int channels,int bits,int format,int rate,int frames,boolean nan)throws IOException {
        int bytes=channels*(bits/8);
        try(DataOutputStream out=new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file)))){
            out.writeBytes("RIFF");writeU32(out,36L+frames*bytes);
            out.writeBytes("WAVE");out.writeBytes("fmt ");
            writeU32(out,16);writeU16(out,format);writeU16(out,channels);
            writeU32(out,rate);writeU32(out,rate*bytes);
            writeU16(out,bytes);writeU16(out,bits);
            out.writeBytes("data");writeU32(out,frames*bytes);
            for(int i=0;i<frames;i++){
                float v=(float)(0.12*Math.sin(2*Math.PI*330*i/rate));
                if(nan&&i==500)v=Float.NaN;
                for(int ch=0;ch<channels;ch++){
                    if(bits==16)writeU16(out,Math.round(v*32767));
                    else writeU32(out,Float.floatToRawIntBits(v));
                }
            }
        }

    }
}
