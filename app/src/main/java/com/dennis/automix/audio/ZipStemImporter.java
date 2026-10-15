package com.dennis.automix.audio;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Extracts ZIP WAVs using the central directory (ZipFile), not ZipInputStream's
 * sequential local-header size accounting. Some third-party stem exporters have
 * local headers with zero/placeholder lengths despite valid central-directory
 * lengths, and Android's ZipInputStream rejects them with "invalid entry size".
 *
 * No path from an untrusted archive is ever used as an output path. Each stem is
 * extracted to a unique, app-private temporary file, with decompression limits
 * and an explicit central-directory CRC check.
 */
public final class ZipStemImporter {
    private ZipStemImporter() {}

    public static final class Stem {
        public final String filename;
        public final File extractedFile;
        Stem(String filename, File extractedFile) {
            this.filename=filename;
            this.extractedFile=extractedFile;
        }
    }

    public static List<Stem> extract(File archive, File privateDirectory,
                                     int existingTracks, int maxTracks,
                                     long maxFileBytes, long maxTotalBytes) throws IOException {
        if(archive==null || !archive.isFile())throw new IOException("ZIP file is unavailable.");
        if(existingTracks<0 || maxTracks<2 || existingTracks>=maxTracks)
            throw new IOException("No remaining stem slots. Clear tracks first.");
        if(maxFileBytes<44 || maxTotalBytes<maxFileBytes)
            throw new IOException("Invalid ZIP import safety limits.");
        if(!privateDirectory.isDirectory() && !privateDirectory.mkdirs())
            throw new IOException("Cannot create private stem folder.");

        ArrayList<Stem> extracted=new ArrayList<>();
        try(ZipFile zip=new ZipFile(archive)) {
            int count=0;
            long declaredTotal=0;
            Enumeration<? extends ZipEntry> entries=zip.entries();
            while(entries.hasMoreElements()) {
                ZipEntry entry=entries.nextElement();
                if(!wavEntry(entry))continue;
                count++;
                if(existingTracks+count>maxTracks)
                    throw new IOException("ZIP contains more WAVs than the "+maxTracks+"-track limit.");
                long size=entry.getSize();
                if(size>maxFileBytes)
                    throw new IOException("WAV exceeds safe per-track size: "+entry.getName());
                if(size>=0) {
                    declaredTotal+=size;
                    if(declaredTotal>maxTotalBytes)
                        throw new IOException("ZIP exceeds safe uncompressed audio size.");
                }
            }
            if(count==0)throw new IOException("The ZIP contains no supported WAV files.");

            long total=0;
            entries=zip.entries();
            while(entries.hasMoreElements()) {
                ZipEntry entry=entries.nextElement();
                if(!wavEntry(entry))continue;
                String normalized=entry.getName().replace('\\','/');
                String name=normalized.substring(normalized.lastIndexOf('/')+1);
                name=name.replaceAll("[^a-zA-Z0-9._ -]","_").trim();
                if(name.isEmpty())throw new IOException("Invalid WAV filename inside ZIP.");
                File temp=File.createTempFile("automix_stem_",".wav",privateDirectory);
                // Register before writing so partial files are removed on any failure.
                extracted.add(new Stem(name,temp));
                long bytes=0;
                CRC32 crc=new CRC32();
                try(InputStream in=new BufferedInputStream(zip.getInputStream(entry),65536);
                    OutputStream out=new BufferedOutputStream(new FileOutputStream(temp),65536)) {
                    byte[] buffer=new byte[65536];
                    int n;
                    while((n=in.read(buffer))!=-1) {
                        if(Thread.currentThread().isInterrupted())
                            throw new IOException("ZIP import cancelled.");
                        bytes+=n;
                        total+=n;
                        if(bytes>maxFileBytes || total>maxTotalBytes)
                            throw new IOException("ZIP audio exceeds safe import size.");
                        crc.update(buffer,0,n);
                        out.write(buffer,0,n);
                    }
                }
                if(bytes<44)throw new IOException("Invalid or empty WAV in ZIP: "+name);
                if(entry.getSize()>=0 && entry.getSize()!=bytes)
                    throw new IOException("ZIP central-directory size mismatch: "+name);
                if(entry.getCrc()>=0 && entry.getCrc()!=crc.getValue())
                    throw new IOException("ZIP CRC mismatch: "+name);
            }
            return extracted;
        } catch(IOException | RuntimeException failure) {
            for(Stem stem:extracted)stem.extractedFile.delete();
            throw failure;
        }
    }

    private static boolean wavEntry(ZipEntry entry) {
        if(entry.isDirectory())return false;
        String path=entry.getName().replace('\\','/').toLowerCase(Locale.ROOT);
        if(path.startsWith("__macosx/") || path.contains("/._"))return false;
        String base=path.substring(path.lastIndexOf('/')+1);
        return base.endsWith(".wav") && !base.startsWith("._");
    }
}
