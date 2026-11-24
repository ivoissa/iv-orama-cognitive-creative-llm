package fr.ivorama.cognitive;

import java.util.*;

/** Deliberately controlled French task DSL; separate syntax from the reasoning engine. */
public final class TaskDialogue {
    private TaskReasoningEngine engine=new TaskReasoningEngine();
    public StandaloneTechnicalAssistant.Response reply(String sentence) {
        if(sentence==null || sentence.isBlank())
            return new StandaloneTechnicalAssistant.Response("Commande vide. Utilise 'aide tache'.","CLARIFICATION",true);
        String s=sentence.trim();
        try {
            if(s.equalsIgnoreCase("aide tache")) return answer("Commandes contrôlées : "
                    + "objectif <fait> ; etape <nom> -> <fait> si <fait1>,<fait2> ; "
                    + "observe <fait> ; observe non <fait> ; retire <fait> ; "
                    + "atteste <etape> ; annule <etape> ; plan ? ; "
                    + "comment atteindre mon objectif ? ; l\'objectif est-il atteint ? ; "
                    + "qu\'est-ce qui bloque ? ; nouvelle tache. "
                    + "Les noms sont des identifiants sans espaces. Le moteur ne réalise aucune action.","AIDE",true);
            if(s.equalsIgnoreCase("nouvelle tache")) {
                engine=new TaskReasoningEngine();
                return answer("Nouvelle session ; aucun fait ni action conservé.","RESET",true);
            }
            if(s.regionMatches(true,0,"objectif ",0,9)) {
                engine.setGoal(s.substring(9).trim());
                return answer("Objectif enregistré ; question FCI "+engine.questionId()+" ouverte.","GOAL",true);
            }
            if(s.regionMatches(true,0,"etape ",0,6)) {
                var m=java.util.regex.Pattern.compile("(?i)^etape\\s+(\\w+)\\s*->\\s*(\\w+)(?:\\s+si\\s+([\\w, ]+))?$",java.util.regex.Pattern.UNICODE_CASE).matcher(s);
                if(!m.matches()) throw new IllegalArgumentException("Syntaxe : etape nom -> resultat si prerequis1,prerequis2");
                String[] req=m.group(3)==null?new String[0]:Arrays.stream(m.group(3).split(","))
                        .map(String::trim).toArray(String[]::new);
                engine.define(m.group(1),m.group(2),req);
                return answer("Étape déclarée : "+m.group(1)+". Elle n'est pas exécutée.","ACTION",true);
            }
            if(s.regionMatches(true,0,"observe ",0,8)) {
                String rest=s.substring(8).trim();
                boolean negative=rest.startsWith("non ");
                engine.observe(negative?rest.substring(4):rest,!negative,"déclaration de l'utilisateur");
                return answer("Observation "+(negative?"négative ":"")+"enregistrée, non vérifiée extérieurement.","EVIDENCE",true);
            }
            if(s.regionMatches(true,0,"retire ",0,7)) {
                String rest=s.substring(7).trim();
                boolean negative=rest.startsWith("non ");
                engine.withdraw(negative?rest.substring(4):rest,!negative);
                return answer("Observation retirée ; dépendances et FCI recalculés.","RETRACTION",true);
            }
            if(s.regionMatches(true,0,"atteste ",0,8)) {
                engine.attest(s.substring(8).trim(),"attestation de l'utilisateur");
                return answer("Étape attestée par l'utilisateur, non exécutée ni vérifiée par le moteur.","ATTESTATION",true);
            }
            if(s.regionMatches(true,0,"annule ",0,7)) {
                engine.withdrawAttestation(s.substring(7).trim());
                return answer("Attestation retirée ; preuves dépendantes invalidées.","RETRACTION",true);
            }
            if(s.equalsIgnoreCase("plan ?") || s.equalsIgnoreCase("plan") ||
                    s.equalsIgnoreCase("ou en est on ?") ||
                    s.equalsIgnoreCase("comment atteindre mon objectif ?") ||
                    s.equalsIgnoreCase("l'objectif est-il atteint ?") ||
                    s.equalsIgnoreCase("qu'est-ce qui bloque ?")) {
                TaskReasoningEngine.Plan p=engine.evaluate();
                String text="FCI "+p.questionId()+" : "+p.fci()+" ; réouverte : "+p.reopened()+" ; état tâche : "+p.state()+".\n"
                        +"RDO — étapes proposées : "+p.orderedActions()+".\n"
                        +"Prérequis à observer : "+p.missingObservations()+".\n"
                        +p.explanation();
                String mode=s.equalsIgnoreCase("l'objectif est-il atteint ?")?"TASK_STATUS"
                        :s.equalsIgnoreCase("qu'est-ce qui bloque ?")?"TASK_OBSTACLES":"TASK_PLAN";
                return answer(text,mode,p.state()!=TaskReasoningEngine.State.ESTABLISHED_CONDITIONALLY);
            }
        } catch(IllegalArgumentException | IllegalStateException ex) {
            return answer("Commande refusée : "+ex.getMessage()+". Aucun fait inventé.","TASK_ERROR",true);
        }
        return answer("Commande de tâche non reconnue ; taper 'aide tache'.","TASK_UNKNOWN",true);
    }
    public TaskReasoningEngine engine() { return engine; }
    public static boolean isTaskCommand(String s) {
        if(s==null) return false;
        String t=s.trim().toLowerCase(Locale.ROOT);
        return t.startsWith("objectif ") || t.startsWith("etape ") || t.startsWith("observe ") ||
                t.startsWith("retire ") || t.startsWith("atteste ") || t.startsWith("annule ") ||
                t.equals("plan") || t.equals("plan ?") || t.equals("aide tache") ||
                t.equals("nouvelle tache") || t.equals("ou en est on ?") ||
                t.equals("comment atteindre mon objectif ?") ||
                t.equals("l'objectif est-il atteint ?") || t.equals("qu'est-ce qui bloque ?");
    }
    private static StandaloneTechnicalAssistant.Response answer(String text,String kind,boolean open) {
        return new StandaloneTechnicalAssistant.Response(text,kind,open);
    }
}
