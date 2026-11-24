package fr.ivorama.cognitive;

import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.nio.file.Path;
import java.io.IOException;

/** Session de français contrôlé, vocabulaire publié par ControlledFrench. */
public final class FrenchConsole {
    private FrenchConsole() {}
    public static void run(InputStream input, PrintStream output) {
        FrenchQuestionDialogue d = new FrenchQuestionDialogue();
        output.println("IV-ORAMA Cognitive 0.6.5 — questions en français CONTRÔLÉ");
        output.println("entite document A | sais Le document A est validé. | sais Tous les documents validés sont archivés.");
        output.println("lexeme approuve homologué homologuée homologués homologuées document,porte | lexeme-ambigu ... | lexique");
        output.println("relation repere document dossier repère | est repéré par | repèrent (pluriel explicite facultatif)");
        output.println("sais Le document A contient le dossier B. | ? Le dossier B est-il contenu dans le document A ?");
        output.println("lexique-sauve chemin.txt | lexique-charge chemin.txt | precise Q1 sens | annule Q1");
        output.println("? Pourquoi le document A est-il archivé ? | ? Comment savoir si le document A est archivé ?");
        output.println("evenement E1 validation dossier A temps 1 connu 2 source paul | evenement E2 annulation E1 dossier A temps 4 connu 5 source paul");
        output.println("? La validation E1 du dossier A est-elle active à t5 selon journal v1 ? | retire-evenement E2");
        output.println("cause C1 E1 E3 connu 9 source paul | ? L'événement E1 a-t-il causé E3 ? | retire-cause C1");
        output.println("? Le document A est-il archivé ? | explique Q1 | questions | retire P1 | observable Le document A est validé. | observe Q1 oui/non | quitter");
        try (Scanner sc = new Scanner(input,StandardCharsets.UTF_8)) {
            while (true) {
                output.print("iv-orama-fr> "); output.flush();
                if(!sc.hasNextLine()) break;
                String line=sc.nextLine().trim();
                if(line.isBlank()) continue;
                if(line.equalsIgnoreCase("quitter")||line.equalsIgnoreCase("exit")) break;
                try {
                    if(line.startsWith("entite ")) {
                        String[] parts=line.substring(7).trim().split("\\s+");
                        if(parts.length!=2) throw new IllegalArgumentException("Usage : entite document A");
                        d.declare(parts[0],parts[1]);
                        output.println("Entité déclarée : "+parts[0]+" "+parts[1]);
                    } else if(line.startsWith("lexeme ") || line.startsWith("lexeme-ambigu ")) {
                        boolean polysemy=line.startsWith("lexeme-ambigu ");
                        String[] fields=line.substring(polysemy ? 14 : 7).trim().split("\\s+");
                        if(fields.length<5 || fields.length>6)
                            throw new IllegalArgumentException("Usage : lexeme sens masculin feminin mascPluriel femPluriel [document,porte,...]");
                        Set<String> kinds=fields.length==6
                                ? Set.copyOf(Arrays.asList(fields[5].split(",")))
                                : Set.of("document","dossier","porte","objet");
                        if(polysemy) d.registerAmbiguousAdjective(fields[0],kinds,fields[1],fields[2],fields[3],fields[4]);
                        else d.registerAdjective(fields[0],kinds,fields[1],fields[2],fields[3],fields[4]);
                        output.println("Sens lexical enregistré (pas de fait créé) : "+fields[0]);
                    } else if(line.startsWith("relation ")) {
                        String[] halves=line.substring(9).trim().split("\\s*\\|\\s*",-1);
                        if((halves.length!=2 && halves.length!=3) || halves[1].isBlank())
                            throw new IllegalArgumentException("Usage : relation concept sujetKind objetKind verbe | passif");
                        String[] parts=halves[0].trim().split("\\s+");
                        if(parts.length!=4) throw new IllegalArgumentException("Usage : relation concept sujetKind objetKind verbe | passif");
                        if(halves.length==3) d.registerRelation(parts[0],parts[1],parts[2],parts[3],halves[1].trim(),halves[2].trim());
                        else d.registerRelation(parts[0],parts[1],parts[2],parts[3],halves[1].trim());
                        output.println("Relation lexicale enregistree, sans fait ni nouvelle question. Empreinte : "+d.relations().fingerprint());
                    } else if(line.startsWith("lexique-sauve ")) {
                        d.saveLexicon(Path.of(line.substring(14).trim()));
                        output.println("Lexique sauvé ; empreinte SHA256 : "+d.lexicalFingerprint());
                    } else if(line.startsWith("lexique-charge ")) {
                        d.loadLexicon(Path.of(line.substring(15).trim()));
                        output.println("Lexique chargé ; empreinte SHA256 : "+d.lexicalFingerprint());
                    } else if(line.equals("lexique")) {
                        for(var e:d.language().lexicon().entries())
                            output.println(e.concept()+" : "+e.masculineSingular()+" / "+e.feminineSingular()
                                    +" / "+e.masculinePlural()+" / "+e.femininePlural()
                                    +" ["+String.join(",",new TreeSet<>(e.allowedKinds()))+"]");
                    } else if(line.startsWith("evenement ")) {
                        d.declareTemporalEvent(line);
                        output.println("Événement déclaré et daté ; aucune certification empirique ; version="+d.temporalLedger().version());
                    } else if(line.startsWith("cause ")) {
                        d.declareCausalClaim(line);
                        output.println("Hypothese causale DECLAREE (non prouvee) ; version="+d.temporalLedger().version());
                    } else if(line.startsWith("retire-cause ")) {
                        d.retractCausalClaim(line.substring(13).trim(),"Retrait utilisateur");
                        output.println("Hypothese causale retiree ; aucune preuve de cause supposee");
                    } else if(line.startsWith("retire-evenement ")) {
                        d.retractTemporalEvent(line.substring(17).trim(),"Retrait utilisateur");
                        output.println("Événement retiré, requêtes temporelles recalculées");
                    } else if(line.startsWith("sais ")) {
                        output.println("Prémisse(s) conditionnelle(s) enregistrée(s) : "+d.teach(line.substring(5)));
                    } else if(line.startsWith("observable ")) {
                        output.println("Observable déclaré : "+d.registerObservable(line.substring(11)));
                    } else if(line.startsWith("? ")) {
                        output.println(d.ask(line.substring(2)).text());
                    } else if(line.startsWith("precise ")) {
                        String[] parts=line.substring(8).trim().split("\\s+");
                        if(parts.length!=2) throw new IllegalArgumentException("Usage : precise Q1 sens");
                        output.println(d.tryClarify(parts[0],parts[1]).reply().text());
                    } else if(line.startsWith("explique ")) {
                        output.println(d.explain(line.substring(8).trim()).text());
                    } else if(line.startsWith("annule ")) {
                        output.println(d.withdraw(line.substring(7).trim()).text());
                    } else if(line.startsWith("observe ")) {
                        String[] parts=line.substring(8).trim().split("\\s+");
                        if(parts.length!=2 || !(parts[1].equalsIgnoreCase("oui")||parts[1].equalsIgnoreCase("non")))
                            throw new IllegalArgumentException("Usage : observe Q1 oui/non");
                        output.println(d.answerObservation(parts[0],parts[1].equalsIgnoreCase("oui"),
                                "Déclaration utilisateur non certifiée").text());
                    } else if(line.startsWith("ignore ")) {
                        output.println(d.decline(line.substring(7).trim()).text());
                    } else if(line.startsWith("retire ")) {
                        d.retract(line.substring(7).trim());
                        output.println("Prémisse retirée, obligations interrogatives réévaluées");
                    } else if(line.equals("questions")) {
                        for(var q:d.cycle().questionActs()) output.println(d.status(q.id()).text());
                    } else output.println("Commande hors grammaire contrôlée ; aucune interprétation supposée.");
                } catch (IllegalArgumentException|IllegalStateException|IOException ex) {
                    output.println("Non interprété : "+ex.getMessage());
                }
            }
        }
    }
}
