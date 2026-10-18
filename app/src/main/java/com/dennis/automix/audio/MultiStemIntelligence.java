package com.dennis.automix.audio;

import java.io.IOException;
import java.util.*;

/**
 * Audition-only, evidence-gated 5–12 stem premix proposals.
 *
 * All processing is deterministic, offline and deliberately subtractive:
 * a maximum of TWO named instrument stems can receive <=0.40 dB of gain
 * reduction and <=0.60 dB of broad 2.5 kHz EQ. Drum, bass and vocal stems
 * never receive automatic changes. A separate transparent reference uses
 * unity gains, zero EQ, and only a shared peak-safety trim.
 *
 * Unlike generic RMS normalization, measurements use simultaneous vocal/
 * instrument passages with an energy threshold for both sources. Ambiguous
 * vocal roles, unlabeled instruments, weak evidence, or limited overlap
 * cause a transparent mix without changes.
 */
public final class MultiStemIntelligence {
    private MultiStemIntelligence() {}

    static final double PRESENCE_HZ=2500.0;
    static final int BLOCK=4096;
    static final int MAX_CORRECTIONS=2;

    public static final class Plan {
        public final double[] gainDb, eqDb, eqHz, overlapSeconds;
        public final boolean recognized, adjusted;
        public final String note;
        Plan(double[] gain, double[] eq, double[] hz, double[] seconds,
             boolean recognized, boolean adjusted, String note) {
            this.gainDb=gain;this.eqDb=eq;this.eqHz=hz;this.overlapSeconds=seconds;
            this.recognized=recognized;this.adjusted=adjusted;this.note=note;
        }
    }

    private static final class Candidate {
        int index;
        String category;
        long coactiveFrames;
        double vocalMid, stemMid, vocalPower, stemPower;
        double evidenceDb, broadDb;
        double gain, eq;
    }

    private static final class Bandpass {
        final double b0,b2,a1,a2;
        double x1,x2,y1,y2;
        Bandpass(int rate,double hz,double q) {
            double omega=2*Math.PI*hz/rate;
            double alpha=Math.sin(omega)/(2*q), a0=1+alpha;
            b0=alpha/a0;b2=-alpha/a0;
            a1=-2*Math.cos(omega)/a0;
            a2=(1-alpha)/a0;
        }
        double process(double x){
            double y=b0*x+b2*x2-a1*y1-a2*y2;
            x2=x1;x1=x;y2=y1;y1=y;return y;
        }
    }

    /** Names are hints, not automatically trustworthy separation labels. */
    static String classify(String name) {
        String s=name.toLowerCase(Locale.ROOT)
                .replaceAll("([a-z])([0-9])","$1 $2")
                .replaceAll("[^a-z0-9]+"," ").trim();
        if(s.matches(".*\\b(vocal|vocals|vox|voice|singer|singing)\\b.*")){
            if(s.matches(".*\\b(back|backing|background|harmony|choir|bgv|bvs)\\b.*"))
                return "backing_vocal";
            if(s.matches(".*\\b(lead|main|primary)\\b.*"))
                return "lead_vocal";
            return "vocal";
        }
        if(s.matches(".*\\b(drum|drums|kick|snare|tom|hihat|hi hat|perc|percussion|cymbal)\\b.*"))
            return "drums";
        if(s.matches(".*\\b(bass|sub|subbass)\\b.*"))return "bass";
        if(s.matches(".*\\b(guitar|guitars|gtr|electric guitar|acoustic guitar)\\b.*"))return "guitar";
        if(s.matches(".*\\b(piano|keyboard|keyboards|keys|organ|rhodes)\\b.*"))return "keys";
        if(s.matches(".*\\b(string|strings|violin|fiddle|cello|viola|orchestra)\\b.*"))return "strings";
        if(s.matches(".*\\b(synth|synths|synthesizer|synthesiser|pad|pads)\\b.*"))return "synth";
        return "other";
    }

    private static boolean correctable(String role){
        return "guitar".equals(role)||"keys".equals(role)||
               "strings".equals(role)||"synth".equals(role);
    }

    public static Plan inspect(List<AutoMixEngine.Track> tracks,
                               List<AutoMixEngine.WavReader> readers,
                               double[] activeRms,int rate)throws IOException {
        final int count=tracks.size();
        if(count<5||count>12||readers.size()!=count||activeRms.length!=count)
            throw new IOException("Multi-stem inspection requires 5–12 tracks.");
        double[] gains=new double[count],eqs=new double[count],hz=new double[count],seconds=new double[count];
        String[] roles=new String[count];
        int explicitLead=-1, leadCount=0, genericVocal=-1, genericCount=0;
        ArrayList<Candidate> candidates=new ArrayList<>();
        for(int i=0;i<count;i++){
            roles[i]=classify(tracks.get(i).name);
            if(roles[i].equals("lead_vocal")){explicitLead=i;leadCount++;}
            else if(roles[i].equals("vocal")){genericVocal=i;genericCount++;}
            if(correctable(roles[i])){
                Candidate candidate=new Candidate();
                candidate.index=i;candidate.category=roles[i];candidates.add(candidate);
            }
        }
        int vocal=-1;
        if(leadCount==1)vocal=explicitLead;
        else if(leadCount==0&&genericCount==1)vocal=genericVocal;
        if(vocal<0)
            return new Plan(gains,eqs,hz,seconds,false,false,
                "No unique lead vocal found; 5–12 stem source levels and EQ preserved.");
        if(candidates.isEmpty())
            return new Plan(gains,eqs,hz,seconds,true,false,
                "No clearly named guitar/keyboard/strings/synth stems; transparent multi-stem mix.");
        if(activeRms[vocal]<0.002)
            return new Plan(gains,eqs,hz,seconds,true,false,
                "Lead vocal energy too low for reliable masking analysis; transparent mix.");

        // Read simultaneous source windows in a single linear pass. No channel
        // summing: independent channel powers avoid false decisions for wide stems.
        final int n=candidates.size();
        float[][] buffers=new float[n+1][BLOCK*2];
        int[] countRead=new int[n+1];
        AutoMixEngine.WavReader[] scans=new AutoMixEngine.WavReader[n+1];
        Bandpass[][] filters=new Bandpass[n+1][2];
        scans[0]=readers.get(vocal);
        for(int k=0;k<n;k++)scans[k+1]=readers.get(candidates.get(k).index);
        for(int k=0;k<scans.length;k++){
            scans[k].rewind();
            for(int ch=0;ch<2;ch++)filters[k][ch]=new Bandpass(rate,PRESENCE_HZ,1.0);
        }
        final long frames=readers.get(vocal).frames;
        long progressed=0;
        while(progressed<frames){
            if(Thread.currentThread().isInterrupted())
                throw new IOException("Multi-stem analysis cancelled.");
            int block=(int)Math.min(BLOCK,frames-progressed);
            for(int k=0;k<scans.length;k++) countRead[k]=scans[k].read(buffers[k],block);
            double[] total=new double[n+1], band=new double[n+1];
            for(int k=0;k<scans.length;k++){
                int ch=scans[k].channels;
                float[] buffer=buffers[k];
                for(int f=0;f<block;f++){
                    double l=f<countRead[k]?buffer[f*ch]:0;
                    double r=f<countRead[k]?(ch==1?l:buffer[f*2+1]):0;
                    double ml=filters[k][0].process(l);
                    double mr=filters[k][1].process(r);
                    total[k]+=l*l+r*r;
                    band[k]+=ml*ml+mr*mr;
                }
            }
            double vRms=Math.sqrt(total[0]/(2.0*block));
            boolean voiceActive=vRms>=Math.max(.002,activeRms[vocal]*.35);
            for(int k=0;k<n;k++){
                Candidate c=candidates.get(k);
                double stemRms=Math.sqrt(total[k+1]/(2.0*block));
                if(voiceActive && stemRms>=Math.max(.002,activeRms[c.index]*.35)){
                    c.coactiveFrames+=block;
                    c.vocalMid+=band[0];c.stemMid+=band[k+1];
                    c.vocalPower+=total[0];c.stemPower+=total[k+1];
                }
            }
            progressed+=block;
        }
        // Require at least three seconds of genuine co-activity and 8% of
        // the total song. Only clearly dominant presence-band interference
        // qualifies. Cut at most two strongest instrument stems.
        ArrayList<Candidate> eligible=new ArrayList<>();
        for(Candidate c:candidates){
            seconds[c.index]=c.coactiveFrames/(double)rate;
            if(c.coactiveFrames<3L*rate || c.coactiveFrames<frames*.08 ||
               c.vocalMid<1e-9*c.coactiveFrames || c.stemMid<1e-9*c.coactiveFrames)
                continue;
            c.evidenceDb=10*Math.log10(c.stemMid/c.vocalMid);
            c.broadDb=10*Math.log10(c.stemPower/c.vocalPower);
            if(c.evidenceDb<3.0)continue;
            c.eq=c.evidenceDb>=7.0?-.60:-.35;
            c.gain=c.broadDb>=6.0?-.40:c.broadDb>=3.0?-.20:0;
            eligible.add(c);
        }
        eligible.sort((a,b)->Double.compare(b.evidenceDb,a.evidenceDb));
        int applied=Math.min(MAX_CORRECTIONS,eligible.size());
        StringBuilder why=new StringBuilder();
        for(int i=0;i<applied;i++){
            Candidate c=eligible.get(i);
            gains[c.index]=c.gain;eqs[c.index]=c.eq;hz[c.index]=PRESENCE_HZ;
            if(why.length()>0)why.append("; ");
            why.append(tracks.get(c.index).name).append(" ")
               .append(String.format(Locale.US,"presence %+,.1f dB, overlap %.1fs -> gain %.2fdB, EQ %.2fdB",
                                     c.evidenceDb,seconds[c.index],c.gain,c.eq));
        }
        String note=applied==0
            ?"Insufficient simultaneous vocal/instrument masking evidence; source levels and tone preserved."
            :"Conservative vocal-presence masking relief: "+why+
              ". Max two instrument changes; vocals, drums and bass untouched. Audition against transparent reference.";
        return new Plan(gains,eqs,hz,seconds,true,applied>0,note);
    }
}
