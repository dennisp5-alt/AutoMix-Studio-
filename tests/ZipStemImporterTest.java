import com.dennis.automix.audio.ZipStemImporter;
import com.dennis.automix.audio.AutoMixEngine;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * Host-only regression test for Suno-style WAV archives with local/central
 * header size disagreements, plus import safety and 12-stem capacity.
 */
public final class ZipStemImporterTest {
    private static void yes(boolean condition,String message) {
        if(!condition)throw new AssertionError(message);
    }
    private static byte[] tinyWav(int seed) throws IOException {
        File temp=File.createTempFile("automix_zip_test_",".wav");
        try {
            try(AutoMixEngine.WavWriter w=new AutoMixEngine.WavWriter(temp,48000,2048)) {
                float[] samples=new float[4096];
                for(int n=0;n<2048;n++) {
                    float v=(float)(.12*Math.sin(2*Math.PI*(150+seed*30)*n/48000.0));
                    samples[2*n]=v; samples[2*n+1]=v*.8f;
                }
                w.write(samples,2048);
            }
            return Files.readAllBytes(temp.toPath());
        } finally {temp.delete();}
    }
    private static File archive(Path directory,int count,boolean strangeHeader) throws Exception {
        File zip=directory.resolve("inputs_"+count+"_"+strangeHeader+".zip").toFile();
        try(ZipOutputStream out=new ZipOutputStream(new FileOutputStream(zip))) {
            for(int n=0;n<count;n++) {
                byte[] samples=tinyWav(n);
                ZipEntry entry=new ZipEntry("suno/song/Track_"+n+".wav");
                // STORED guarantees a meaningful local size that we can
                // deliberately zero, while the central directory stays correct.
                entry.setMethod(ZipEntry.STORED);
                entry.setSize(samples.length);
                entry.setCompressedSize(samples.length);
                CRC32 crc=new CRC32();crc.update(samples);
                entry.setCrc(crc.getValue());
                out.putNextEntry(entry);
                out.write(samples);
                out.closeEntry();
            }
        }
        if(strangeHeader) {
            try(RandomAccessFile f=new RandomAccessFile(zip,"rw")) {
                f.seek(0);
                yes(f.readInt()==0x504b0304,"Expect ZIP local file header");
                // Little-endian uncompressed size of the first entry. Deliberately
                // make it inconsistent with the fully valid central-directory size.
                f.seek(22);
                f.write(new byte[]{0,0,0,0});
            }
        }
        return zip;
    }
    private static void remove(List<ZipStemImporter.Stem> stems) {
        for(ZipStemImporter.Stem s:stems)s.extractedFile.delete();
    }
    private static long stemsIn(Path dir)throws Exception {
        try(java.util.stream.Stream<Path> paths=Files.list(dir)) {
            return paths.filter(x->x.getFileName().toString().startsWith("automix_stem_")).count();
        }
    }
    public static void main(String[] args)throws Exception {
        Path home=Files.createTempDirectory("automix_zip_regression");
        Path extracted=Files.createDirectory(home.resolve("out"));
        File strange=archive(home,2,true);
        try(ZipFile zf=new ZipFile(strange)) {
            ZipEntry e=zf.entries().nextElement();
            yes(e.getSize()>44,"Central directory still has real uncompressed size");
        }
        List<ZipStemImporter.Stem> imported=ZipStemImporter.extract(
            strange,extracted.toFile(),0,12,1_300_000_000L,3_000_000_000L);
        yes(imported.size()==2,"Read malformed local-header ZIP using central sizes");
        for(int i=0;i<2;i++) {
            byte[] expected=tinyWav(i);
            yes(Arrays.equals(expected,Files.readAllBytes(imported.get(i).extractedFile.toPath())),
                "Audio must remain byte-for-byte identical despite broken local size");
            try(AutoMixEngine.WavReader wav=new AutoMixEngine.WavReader(imported.get(i).extractedFile)){
                yes(wav.frames==2048&&wav.sampleRate==48000,"Extracted WAV must remain valid");
            }
        }
        remove(imported);
        for(int count:new int[]{4,8,12}){
            File zip=archive(home,count,false);
            List<ZipStemImporter.Stem> stems=ZipStemImporter.extract(
                zip,extracted.toFile(),0,12,1_300_000_000L,3_000_000_000L);
            yes(stems.size()==count,"Extract all "+count+" WAVs");
            remove(stems);
        }
        boolean rejected=false;
        try{ZipStemImporter.extract(archive(home,13,false),
                                  extracted.toFile(),0,12,1_300_000_000L,3_000_000_000L);}
        catch(IOException expected){rejected=true;}
        yes(rejected,"Reject 13 stems");
        yes(stemsIn(extracted)==0,"No leftover partial stems after count rejection");

        // Validate central CRC even if ZipFile itself does not verify stream CRC.
        File corrupted=archive(home,2,false);
        try(RandomAccessFile raf=new RandomAccessFile(corrupted,"rw")){
            raf.seek(26);
            int nameLo=raf.readUnsignedByte(),nameHi=raf.readUnsignedByte();
            int nameLength=nameLo|(nameHi<<8);
            int extraLo=raf.readUnsignedByte(),extraHi=raf.readUnsignedByte();
            int extraLength=extraLo|(extraHi<<8);
            long firstSample=30L+nameLength+extraLength+44;
            raf.seek(firstSample);
            int byteValue=raf.readUnsignedByte();
            raf.seek(firstSample);raf.writeByte(byteValue^1);
        }
        boolean crcRejected=false;
        try{ZipStemImporter.extract(corrupted,extracted.toFile(),0,12,
                                  1_300_000_000L,3_000_000_000L);}
        catch(IOException expected){crcRejected=expected.getMessage().contains("CRC");}
        yes(crcRejected,"Corrupted WAV data must fail archive CRC");
        yes(stemsIn(extracted)==0,"CRC failure cleans up incomplete stems");

        File small=archive(home,2,false);
        boolean rejectedBudget=false;
        try{ZipStemImporter.extract(small,extracted.toFile(),0,12,200,3_000_000_000L);}
        catch(IOException expected){rejectedBudget=true;}
        yes(rejectedBudget,"Track size limits must be enforced");
        yes(stemsIn(extracted)==0,"Budget failure cleans up incomplete stems");

        System.out.println("PASS: ZIP central-directory placeholder-size compatibility; byte-identical WAV, 4/8/12 capacity, 13 refusal, CRC safety, size limits and cleanup");
    }
}
