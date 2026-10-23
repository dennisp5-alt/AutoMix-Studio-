import com.dennis.automix.audio.AutoMixEngine;
import com.dennis.automix.audio.StereoPremasterEngine;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

public final class StereoPremasterEngineTest {
    static void ok(boolean condition,String message) {
        if(!condition)throw new AssertionError(message);
    }
    static String hash(File f)throws Exception {
        MessageDigest dig=MessageDigest.getInstance("SHA-256");
        try(InputStream in=new FileInputStream(f)){
            byte[] b=new byte[32768];int n;
            while((n=in.read(b))!=-1)dig.update(b,0,n);
        }
        return HexFormat.of().formatHex(dig.digest());
    }
    static File make(Path dir,String filename,int seconds,double amplitude,
                     double dc,boolean invert,boolean boomy)throws Exception {
        int sr=48000,frames=sr*seconds;
        File output=dir.resolve(filename).toFile();
        try(AutoMixEngine.WavWriter writer=new AutoMixEngine.WavWriter(output,sr,frames)) {
            float[] data=new float[4096*2];
            for(int n=0;n<frames;n+=4096){
                int count=Math.min(4096,frames-n);
                for(int i=0;i<count;i++){
                    double time=(n+i)/(double)sr;
                    double low=Math.sin(2*Math.PI*280*time)*amplitude;
                    double mid=Math.sin(2*Math.PI*1350*time)*amplitude*(boomy?.008:0.7);
                    double sample=low+mid;
                    data[2*i]=(float)(sample+dc);
                    data[2*i+1]=(float)((invert?-sample:sample*.94)+dc);
                }
                writer.write(data,count);
            }
        }
        return output;
    }
    public static void main(String[] args)throws Exception {
        Path dir=Files.createTempDirectory("automix-single-wav");
        File original=make(dir,"clean.wav",4,.42,0,false,false);
        File out=dir.resolve("clean_premaster.wav").toFile();
        String before=hash(original);
        StereoPremasterEngine.Report r=StereoPremasterEngine.prepare(original,out,null);
        ok(hash(original).equals(before),"The original WAV must not change");
        ok(r.outputPeakDbfs<=-5.99,"Sample-peak headroom");
        ok(r.eqAppliedDb==0,"Typical source must keep its EQ");
        ok(!r.dcCorrected,"No DC repair on healthy source");
        ok(r.frames==4L*48000,"Frame count");
        ok(r.outputGainDb<=0.000001,"Do not increase loudness");
        ok(r.json().contains("\"true_peak_measured\":false"),"Explicit measurement limitation");
        try(AutoMixEngine.WavReader reader=new AutoMixEngine.WavReader(out)){
            ok(reader.bits==24&&reader.channels==2&&reader.sampleRate==48000,"24-bit stereo export");
        }
        // Unhealthy DC offset should be reduced without mixing stereo channels.
        File offset=make(dir,"offset.wav",3,.18,.020,false,false);
        String originalHash=hash(offset);
        StereoPremasterEngine.Report dc=StereoPremasterEngine.prepare(offset,dir.resolve("offset_premaster.wav").toFile(),null);
        ok(dc.dcCorrected,"DC offset must be detected");
        ok(originalHash.equals(hash(offset)),"DC scan never overwrites source");
        ok(dc.outputPeakDbfs<=-5.99,"DC case output safety");
        // Deliberate inverted right channel must be reported, never silently narrowed.
        File antiphase=make(dir,"antiphase.wav",3,.21,0,true,false);
        StereoPremasterEngine.Report mono=StereoPremasterEngine.prepare(antiphase,dir.resolve("antiphase_premaster.wav").toFile(),null);
        ok(mono.questionableMono,"Flag destructive mono compatibility");
        ok(mono.eqAppliedDb==0,"Poor mono must not trigger tonal corrections");
        // Sustained low-mid emphasis, with enough musical crest, allows only a tiny correction.
        File muddy=make(dir,"excess_lowmid.wav",12,.25,0,false,true);
        StereoPremasterEngine.Report tone=StereoPremasterEngine.prepare(muddy,dir.resolve("excess_lowmid_premaster.wav").toFile(),null);
        ok(tone.eqAppliedDb>=-.501&&tone.eqAppliedDb<=0,"EQ bounded to half dB");
        ok(tone.outputPeakDbfs<=-5.99,"EQ output headroom");
        // Input/output alias protection.
        boolean refused=false;
        try{StereoPremasterEngine.prepare(original,original,null);}
        catch(IOException expected){refused=true;}
        ok(refused,"Cannot overwrite original WAV");
        System.out.println("STEREO_PREMASTER_QC_PASS preserving source, headroom, mono, DC, conservative EQ");
    }
}
