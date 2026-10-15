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
        System.out.println("PASS: WAV IO (24-bit PCM, mono 16-bit, float32), 2/4-track mix, peak and gain guards, failure preservation, format validation");
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
