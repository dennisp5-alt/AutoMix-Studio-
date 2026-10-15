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
        boolean rejected=false;
        try{
            AutoMixEngine.mix(Arrays.asList(
                new AutoMixEngine.Track("A",vocal),
                new AutoMixEngine.Track("B",tone(tmp,"short.wav",200,.2,2,rate,rate/2))),
                tmp.resolve("should_fail.wav").toFile(),null);
        }catch(IOException expected){rejected=true;}
        verify(rejected,"Mismatch must be rejected");
        verify(!Files.exists(tmp.resolve("should_fail.wav")),"Failure cleanup");
        System.out.println("PASS: WAV IO, 4-track offline mix, peak guard, gain guard, stereo and mismatch rejection");
    }
}
