package com.ivorama.nlp.tools;

import edu.mit.jwi.Dictionary;
import edu.mit.jwi.item.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** A real local JWI lookup, independent of Wikipedia and the text transformation policy. */
public final class JwiDictionaryProbe {
    public static void main(String[] args) throws Exception {
        if(args.length<3)throw new IllegalArgumentException("Usage: JwiDictionaryProbe <dict-directory> <NOUN|VERB|ADJECTIVE|ADVERB> <lemma>");
        Path path=Path.of(args[0]).toAbsolutePath().normalize();String lemma=String.join(" ",Arrays.copyOfRange(args,2,args.length));
        POS pos=POS.valueOf(args[1].toUpperCase(Locale.ROOT));
        var dictionary=new Dictionary(path.toUri().toURL());
        try {
            dictionary.setCharset(StandardCharsets.UTF_8);dictionary.open();
            var entry=dictionary.getIndexWord(ExportFrenchJwiDictionary.normalizedLemma(lemma),pos);
            System.out.println("JWI_DIRECTORY="+path);System.out.println("JWI_OPEN="+dictionary.isOpen());
            if(entry==null){System.out.println("JWI_ENTRY=NOT_FOUND");return;}
            System.out.println("JWI_ENTRY="+entry.getLemma()+"; SENSES="+entry.getWordIDs().size());
            for(var id:entry.getWordIDs())System.out.println("SYNSET="+id.getSynsetID()+"; WORDS="+dictionary.getWord(id).getSynset().getWords().stream().map(IWord::getLemma).toList());
        } finally { dictionary.close(); }
    }
}
