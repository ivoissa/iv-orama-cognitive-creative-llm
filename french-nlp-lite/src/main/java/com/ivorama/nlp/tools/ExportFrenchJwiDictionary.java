package com.ivorama.nlp.tools;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Deterministic lexical-only Princeton serialization of the supplied French OMW synsets. */
public final class ExportFrenchJwiDictionary {
    private ExportFrenchJwiDictionary() {}
    private static final String HEADER="  1 IV-ORAMA French OMW lexical export; see ../PROVENANCE.txt and LICENSE.html\n";
    public static String normalizedLemma(String value) { return value.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+","_"); }
    public static Map<String,SortedSet<String>> read(InputStream source) throws IOException {
        var synsets=new TreeMap<String,SortedSet<String>>();
        try(var reader=new BufferedReader(new InputStreamReader(source,StandardCharsets.UTF_8))) {
            String line;while((line=reader.readLine())!=null) {
                if(line.isBlank()||line.startsWith("#"))continue;
                var fields=line.split("\t",3);if(fields.length!=3||!fields[1].equals("fra:lemma"))continue;
                if(!fields[0].matches("[0-9]{8}-[navr]"))throw new IOException("Unsupported source synset ID: "+fields[0]);
                String lemma=normalizedLemma(fields[2]);if(lemma.isBlank()||(lemma.indexOf('|')>=0||lemma.indexOf('%')>=0))continue; // Recorded verbatim in EXCLUDED.tsv by export().
                synsets.computeIfAbsent(fields[0],k->new TreeSet<>()).add(lemma);
            }
        }
        return Collections.unmodifiableMap(synsets);
    }
    public static void export(InputStream source,Path directory) throws Exception {
        byte[] bytes=source.readAllBytes();var synsets=read(new ByteArrayInputStream(bytes));Files.createDirectories(directory);
        var excluded=new StringBuilder("source_synset\tlemma\treason\n");
        for(String line:new String(bytes,StandardCharsets.UTF_8).split("\n")) {
            var fields=line.split("\t",3);
            if(fields.length==3&&fields[1].equals("fra:lemma")&&(fields[2].isBlank()||(fields[2].indexOf('|')>=0||fields[2].indexOf('%')>=0)))
                excluded.append(fields[0]).append('\t').append(fields[2]).append("\tUNREPRESENTABLE_PRINCETON_DELIMITER\n");
        }
        Files.writeString(directory.resolve("EXCLUDED.tsv"),excluded,StandardCharsets.UTF_8);
        var mapping=new StringBuilder("source_synset\tpos\texport_offset\n");
        for(char pos:new char[]{'n','v','a','r'}) {
            if(synsets.keySet().stream().noneMatch(id->id.charAt(9)==pos))continue;
            String suffix=switch(pos){case 'n'->"noun";case 'v'->"verb";case 'a'->"adj";default->"adv";};
            int lexFile=switch(pos){case 'n'->3;case 'v'->29;case 'a'->0;default->2;};
            int senseType=switch(pos){case 'n'->1;case 'v'->2;case 'a'->3;default->4;};
            var index=new TreeMap<String,List<Integer>>();
            var data=new ByteArrayOutputStream();data.write(HEADER.getBytes(StandardCharsets.UTF_8));
            for(var entry:synsets.entrySet())if(entry.getKey().charAt(9)==pos) {
                var words=entry.getValue();if(words.size()>255)throw new IOException("Synset too large for Princeton format");
                int offset=data.size();if(offset>99_999_999)throw new IOException("Dictionary exceeds 8-digit offsets");
                var line=new StringBuilder(String.format(Locale.ROOT,"%08d %02d %c %02x",offset,lexFile,pos,words.size()));
                for(String word:words) {
                    int lexicalId=0; // No canonical Princeton sense keys are claimed or exported.
                    line.append(String.format(Locale.ROOT," %s %02x",word,lexicalId));
                    index.computeIfAbsent(word,k->new ArrayList<>()).add(offset);

                }
                line.append(pos=='v'?" 000 00 | ":" 000 | ").append("OMW-fra source ").append(entry.getKey()).append('\n');
                data.write(line.toString().getBytes(StandardCharsets.UTF_8));
                mapping.append(entry.getKey()).append('\t').append(pos).append('\t').append(offset).append('\n');
            }
            Files.write(directory.resolve("data."+suffix),data.toByteArray());
            var lines=new StringBuilder(HEADER);
            for(var entry:index.entrySet()) {
                int count=entry.getValue().size();lines.append(entry.getKey()).append(' ').append(pos).append(' ').append(count).append(" 0 ").append(count).append(" 0");
                for(int offset:entry.getValue())lines.append(String.format(Locale.ROOT," %08d",offset));lines.append('\n');
            }
            Files.writeString(directory.resolve("index."+suffix),lines,StandardCharsets.UTF_8);

        }
        Files.deleteIfExists(directory.resolve("index.sense")); // French OMW does not provide canonical Princeton sense keys.
        Files.writeString(directory.resolve("SOURCE-SYNSETS.tsv"),mapping,StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("EXPORT.properties"),"format=ivorama-omw-fr-lexical-v1\ncharset=UTF-8\nsource_sha256="
            +HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))+"\nsynsets="+synsets.size()+"\npointers=0\nsense_keys=unsupported\nlexfile_numbers=format_placeholders_not_source_annotations\n",StandardCharsets.UTF_8);
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("Usage: ExportFrenchJwiDictionary data/wordnet-fr/dict");
        try(var input=ExportFrenchJwiDictionary.class.getResourceAsStream("/lexicon/omw-fra.tab")) {
            if(input==null)throw new IOException("French OMW resource missing");export(input,Path.of(args[0]));
        }
        System.out.println("French OMW lexical export written to "+Path.of(args[0]).toAbsolutePath());
    }
}
