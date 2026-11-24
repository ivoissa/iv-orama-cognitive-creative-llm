package fr.ivorama.cognitive.voisins;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Extrait automatiquement les mots voisins d'un mot et écrit voisins.csv pour NuageDeMots.java.
 *
 * Lancement (Java 17+, un seul fichier) :
 *   java ExtracteurVoisins.java vert --conllu corpus/ [--lexique lexique.csv] [--sortie voisins.csv]
 *                                     [--fenetre 5] [--min-count 2] [--top 40] [--distance 2] [--decay 0.5]
 *   java ExtracteurVoisins.java --selftest
 *
 * Entrée : fichiers au format CoNLL-U (Universal Dependencies), un fichier ou un dossier de .conllu.
 *   Sources : les corpus UD français (GSD, Sequoia, ParTUT, Rhapsodie...), annotés à la main, ou la sortie
 *   de n'importe quel analyseur qui écrit du CoNLL-U (CoreNLP, UDPipe, Stanza...).
 *
 * Liste A : les étiquettes de dépendance sont traduites en relations de A.json ; score = NPMI(mot, voisin).
 * Liste B : collocations (fenêtre de mots pleins, score = NPMI). Les autres relations lexicales
 *   (synonymie, antonymie, hyperonymie...) viennent d'un fichier --lexique au même format que voisins.csv :
 *   mot;sens;probabilite_sens;voisin;liste;relation;score
 *   (à produire à partir de WOLF, JeuxDeMots ou toute autre ressource ; aucune n'est incluse ici).
 * Voisins indirects : les mots à distance 2..--distance dans l'arbre de dépendances reçoivent la relation
 *   « Voisin indirect », avec un score multiplié par decay^(distance-1) (--distance 1 les désactive).
 * Nom de la relation = fonction du dépendant ; pour un attribut, « Attribut du sujet » dans les deux sens.
 */
public final class ExtracteurVoisins {

    /** i = position dans la phrase ; tete = position du gouverneur (== i pour la racine). */
    record Tok(int i, String forme, String lemme, String pos, String dep, int tete) {}

    /** dist = distance dans l'arbre de dépendances (1 = lien direct). */
    record Paire(String voisin, String relation, int dist) {}

    record Lien(String liste, String relation, String voisin, double score) {}

    static final Set<String> IGNORES = Set.of("PUNCT", "SPACE", "SYM", "NUM", "X");
    static final Set<String> CONTENU = Set.of("NOUN", "VERB", "ADJ", "ADV", "PROPN");

    /** Étiquette UD du dépendant -> nom de relation dans A.json. */
    static final Map<String, String> DEP_RELATION = Map.ofEntries(
            Map.entry("det", "Déterminant"), Map.entry("amod", "Épithète"),
            Map.entry("nmod", "Complément du nom"), Map.entry("appos", "Apposition"),
            Map.entry("nsubj", "Sujet"), Map.entry("nsubj:pass", "Sujet"), Map.entry("csubj", "Sujet"),
            Map.entry("obj", "COD"), Map.entry("iobj", "COS"), Map.entry("obl:arg", "COI"),
            Map.entry("obl:agent", "Complément d'agent"), Map.entry("obl", "Complément circonstanciel"),
            Map.entry("advmod", "Adverbe"), Map.entry("case", "Préposition"),
            Map.entry("aux", "Auxiliaire et participe"), Map.entry("aux:tense", "Auxiliaire et participe"),
            Map.entry("aux:pass", "Auxiliaire et participe"), Map.entry("conj", "Coordination"),
            Map.entry("cc", "Coordination"), Map.entry("acl", "Subordonnée relative"),
            Map.entry("acl:relcl", "Subordonnée relative"), Map.entry("ccomp", "Subordonnée complétive"),
            Map.entry("xcomp", "Subordonnée complétive"), Map.entry("advcl", "Subordonnée circonstancielle"),
            Map.entry("parataxis", "Juxtaposition"), Map.entry("vocative", "Apostrophe"),
            Map.entry("cop", "Copule"));

    static String relationDe(String dep) {
        String r = DEP_RELATION.get(dep);
        if (r == null) {
            int k = dep.indexOf(':');
            if (k > 0) {
                r = DEP_RELATION.get(dep.substring(0, k));
            }
        }
        return r;
    }

    // ---------------------------------------------------------------- lecture CoNLL-U

    static List<List<Tok>> lirePhrases(List<String> lignes) {
        List<List<Tok>> phrases = new ArrayList<>();
        List<String[]> mots = new ArrayList<>();
        for (String brut : lignes) {
            String l = brut.strip();
            if (l.startsWith("#")) {
                continue;
            }
            if (l.isEmpty()) {
                fermer(mots, phrases);
                continue;
            }
            String[] c = brut.split("\t", -1);
            if (c.length < 8 || c[0].contains("-") || c[0].contains(".")) {
                continue; // mots multiples (« au » = « à le ») et noeuds vides ignorés
            }
            mots.add(c);
        }
        fermer(mots, phrases);
        return phrases;
    }

    private static void fermer(List<String[]> mots, List<List<Tok>> phrases) {
        if (mots.isEmpty()) {
            return;
        }
        Map<Integer, Integer> position = new HashMap<>();
        for (int k = 0; k < mots.size(); k++) {
            position.put(Integer.parseInt(mots.get(k)[0]), k);
        }
        List<Tok> p = new ArrayList<>();
        for (int k = 0; k < mots.size(); k++) {
            String[] c = mots.get(k);
            String lemme = (c[2].equals("_") ? c[1] : c[2]).toLowerCase(Locale.ROOT);
            int tete = c[6].equals("_") ? k : position.getOrDefault(Integer.parseInt(c[6]), k);
            p.add(new Tok(k, c[1].toLowerCase(Locale.ROOT), lemme, c[3], c[7], tete));
        }
        phrases.add(p);
        mots.clear();
    }

    // ---------------------------------------------------------------- extraction

    private static boolean aCop(Tok t, Map<Integer, List<Tok>> enfants) {
        return enfants.getOrDefault(t.i(), List.of()).stream().anyMatch(c -> c.dep().equals("cop"));
    }

    private static boolean valide(Tok t, String cible) {
        return !IGNORES.contains(t.pos()) && !t.lemme().isEmpty() && !t.lemme().equals(cible);
    }

    /** Voisins à distance 2..distMax dans l'arbre de dépendances (en ignorant la ponctuation). */
    static List<Paire> pairesIndirectes(List<Tok> p, String cible, int distMax, Map<Integer, List<Tok>> enfants) {
        List<Paire> out = new ArrayList<>();
        for (Tok t : p) {
            if (!t.lemme().equals(cible)) {
                continue;
            }
            Map<Integer, Integer> dist = new HashMap<>();
            java.util.ArrayDeque<Integer> file = new java.util.ArrayDeque<>();
            dist.put(t.i(), 0);
            file.add(t.i());
            while (!file.isEmpty()) {
                int x = file.poll();
                int d = dist.get(x);
                if (d >= distMax) {
                    continue;
                }
                List<Integer> suivants = new ArrayList<>();
                Tok tx = p.get(x);
                if (tx.tete() != tx.i()) {
                    suivants.add(tx.tete());
                }
                for (Tok c : enfants.getOrDefault(x, List.of())) {
                    suivants.add(c.i());
                }
                for (int y : suivants) {
                    Tok ty = p.get(y);
                    if (dist.containsKey(y) || IGNORES.contains(ty.pos())) {
                        continue;
                    }
                    dist.put(y, d + 1);
                    file.add(y);
                    if (d + 1 >= 2 && valide(ty, cible)) {
                        out.add(new Paire(ty.lemme(), "Voisin indirect", d + 1));
                    }
                }
            }
        }
        return out;
    }

    static List<Paire> pairesSyntaxiques(List<Tok> p, String cible, int distMax) {
        Map<Integer, List<Tok>> enfants = new HashMap<>();
        for (Tok t : p) {
            if (t.tete() != t.i()) {
                enfants.computeIfAbsent(t.tete(), k -> new ArrayList<>()).add(t);
            }
        }
        List<Paire> out = new ArrayList<>();
        for (Tok t : p) {
            if (!t.lemme().equals(cible)) {
                continue;
            }
            if (t.tete() != t.i()) {
                Tok h = p.get(t.tete());
                String rel = t.dep().startsWith("nsubj") && aCop(h, enfants)
                        ? "Attribut du sujet" : relationDe(t.dep());
                if (rel != null && valide(h, cible)) {
                    out.add(new Paire(h.lemme(), rel, 1));
                }
            }
            for (Tok c : enfants.getOrDefault(t.i(), List.of())) {
                String rel = c.dep().startsWith("nsubj") && aCop(t, enfants)
                        ? "Attribut du sujet" : relationDe(c.dep());
                if (rel != null && valide(c, cible)) {
                    out.add(new Paire(c.lemme(), rel, 1));
                }
            }
        }
        if (distMax >= 2) {
            out.addAll(pairesIndirectes(p, cible, distMax, enfants));
        }
        return out;
    }

    static List<String> pairesCollocation(List<Tok> p, String cible, int fenetre) {
        List<String> out = new ArrayList<>();
        for (int k = 0; k < p.size(); k++) {
            if (!p.get(k).lemme().equals(cible)) {
                continue;
            }
            for (int j = Math.max(0, k - fenetre); j < Math.min(p.size(), k + fenetre + 1); j++) {
                Tok v = p.get(j);
                if (j != k && CONTENU.contains(v.pos()) && !v.lemme().equals(cible)) {
                    out.add(v.lemme());
                }
            }
        }
        return out;
    }

    /** Information mutuelle ponctuelle normalisée, ramenée à [0, 1] (les valeurs négatives donnent 0). */
    static double npmi(long cUv, long cU, long cV, long n) {
        if (Math.min(cUv, Math.min(cU, cV)) <= 0 || cUv >= n) {
            return 0.0;
        }
        double pUv = (double) cUv / n;
        double pmi = Math.log(pUv / (((double) cU / n) * ((double) cV / n)));
        return Math.min(1.0, Math.max(0.0, pmi / -Math.log(pUv)));
    }

    static final class Compteurs {
        final Map<String, Integer> lemmes = new HashMap<>();
        final Map<Paire, Integer> synt = new HashMap<>();
        final Map<String, Integer> coll = new HashMap<>();
        final Map<String, Map<String, Integer>> formes = new HashMap<>();
        long n;

        void ajouter(List<Tok> phrase, String cible, int fenetre, int distMax) {
            for (Tok t : phrase) {
                if (!IGNORES.contains(t.pos())) {
                    lemmes.merge(t.lemme(), 1, Integer::sum);
                    formes.computeIfAbsent(t.forme(), k -> new HashMap<>()).merge(t.lemme() + "|" + t.pos(), 1, Integer::sum);
                    n++;
                }
            }
            for (Paire p : pairesSyntaxiques(phrase, cible, distMax)) {
                synt.merge(p, 1, Integer::sum);
            }
            for (String v : pairesCollocation(phrase, cible, fenetre)) {
                coll.merge(v, 1, Integer::sum);
            }
        }

        List<Lien> liens(String cible, int minCount, int top, double decay) {
            long u = lemmes.getOrDefault(cible, 0);
            List<Lien> a = new ArrayList<>();
            List<Lien> b = new ArrayList<>();
            for (Map.Entry<Paire, Integer> e : synt.entrySet()) {
                double s = e.getValue() < minCount ? 0
                        : npmi(e.getValue(), u, lemmes.getOrDefault(e.getKey().voisin(), 0), n);
                if (s > 0) {
                    a.add(new Lien("A", e.getKey().relation(), e.getKey().voisin(),
                            s * Math.pow(decay, e.getKey().dist() - 1)));
                }
            }
            for (Map.Entry<String, Integer> e : coll.entrySet()) {
                double s = e.getValue() < minCount ? 0
                        : npmi(e.getValue(), u, lemmes.getOrDefault(e.getKey(), 0), n);
                if (s > 0) {
                    b.add(new Lien("B", "Collocation", e.getKey(), s));
                }
            }
            List<Lien> out = new ArrayList<>();
            for (List<Lien> l : List.of(a, b)) {
                l.sort(Comparator.comparingDouble(Lien::score).reversed());
                out.addAll(l.subList(0, Math.min(top, l.size())));
            }
            return out;
        }
    }

    // ---------------------------------------------------------------- entrées / sorties

    static List<Path> fichiers(List<String> chemins) throws IOException {
        List<Path> out = new ArrayList<>();
        for (String c : chemins) {
            Path p = Path.of(c);
            if (Files.isDirectory(p)) {
                try (Stream<Path> s = Files.walk(p)) {
                    s.filter(f -> f.toString().endsWith(".conllu")).sorted().forEach(out::add);
                }
            } else {
                out.add(p);
            }
        }
        return out;
    }

    static List<String> lexique(Path f, String cible) throws IOException {
        List<String> out = new ArrayList<>();
        int n = 0;
        for (String brut : Files.readAllLines(f, StandardCharsets.UTF_8)) {
            n++;
            String s = brut.strip();
            if (s.isEmpty() || s.startsWith("#")) {
                continue;
            }
            String[] c = s.split(";", -1);
            if (c.length != 7) {
                throw new IllegalArgumentException("Lexique, ligne " + n + " : 7 colonnes attendues");
            }
            if (!c[0].strip().equals("mot") && c[0].strip().equalsIgnoreCase(cible)) {
                out.add(s);
            }
        }
        return out;
    }

    static void ecrire(Path sortie, String cible, List<Lien> liens, List<String> lex) throws IOException {
        StringBuilder b = new StringBuilder("# genere par ExtracteurVoisins.java\n");
        b.append("mot;sens;probabilite_sens;voisin;liste;relation;score\n");
        for (Lien l : liens) {
            b.append(String.format(Locale.ROOT, "%s;*;1.0;%s;%s;%s;%.4f%n", cible, l.voisin(), l.liste(), l.relation(), l.score()));
        }
        for (String s : lex) {
            b.append(s).append('\n');
        }
        Files.writeString(sortie, b.toString(), StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- test interne

    private static void verifier(boolean ok, String msg) {
        if (!ok) {
            throw new AssertionError("Échec : " + msg);
        }
    }

    static void selftest() {
        String s1 = "1\tLe\tle\tDET\t_\t_\t2\tdet\t_\t_\n2\tbois\tbois\tNOUN\t_\t_\t4\tnsubj\t_\t_\n"
                + "3\test\têtre\tAUX\t_\t_\t4\tcop\t_\t_\n4\tvert\tvert\tADJ\t_\t_\t0\troot\t_\t_\n\n";
        String s2 = "# sent_id = 2\n1\tIl\til\tPRON\t_\t_\t2\tnsubj\t_\t_\n2\tva\taller\tVERB\t_\t_\t0\troot\t_\t_\n"
                + "3-4\tau\t_\t_\t_\t_\t_\t_\t_\t_\n3\tà\tà\tADP\t_\t_\t5\tcase\t_\t_\n"
                + "4\tle\tle\tDET\t_\t_\t5\tdet\t_\t_\n5\tbois\tbois\tNOUN\t_\t_\t2\tobl:mod\t_\t_\n\n";
        List<List<Tok>> ph = lirePhrases(List.of((s1 + s2).split("\n", -1)));
        verifier(ph.size() == 2 && ph.get(1).size() == 5, "lecture CoNLL-U (mot multiple ignoré)");
        Set<Paire> direct = Set.of(new Paire("bois", "Attribut du sujet", 1), new Paire("être", "Copule", 1));
        verifier(Set.copyOf(pairesSyntaxiques(ph.get(0), "vert", 1)).equals(direct), "attribut et copule");
        verifier(Set.copyOf(pairesSyntaxiques(ph.get(0), "vert", 2))
                .equals(Set.of(new Paire("bois", "Attribut du sujet", 1), new Paire("être", "Copule", 1),
                        new Paire("le", "Voisin indirect", 2))), "voisin indirect de vert");
        verifier(Set.copyOf(pairesSyntaxiques(ph.get(0), "bois", 2))
                .equals(Set.of(new Paire("le", "Déterminant", 1), new Paire("vert", "Attribut du sujet", 1),
                        new Paire("être", "Voisin indirect", 2))), "voisin indirect de bois");
        verifier(Set.copyOf(pairesSyntaxiques(ph.get(1), "bois", 1))
                .equals(Set.of(new Paire("à", "Préposition", 1), new Paire("le", "Déterminant", 1),
                        new Paire("aller", "Complément circonstanciel", 1))), "repli obl:mod -> obl");
        verifier(Set.copyOf(pairesCollocation(ph.get(0), "bois", 2)).equals(Set.of("vert")), "collocation");
        verifier(Math.abs(npmi(5, 10, 10, 100) - 0.5372) < 1e-3, "npmi");
        Compteurs c = new Compteurs();
        for (int k = 0; k < 3; k++) {
            c.ajouter(ph.get(0), "vert", 5, 2);
        }
        boolean a = false;
        boolean b = false;
        boolean ind = false;
        for (Lien l : c.liens("vert", 2, 10, 0.5)) {
            a |= l.liste().equals("A") && l.voisin().equals("bois") && Math.abs(l.score() - 1) < 1e-9;
            b |= l.liste().equals("B") && l.voisin().equals("bois") && Math.abs(l.score() - 1) < 1e-9;
            ind |= l.relation().equals("Voisin indirect") && l.voisin().equals("le") && Math.abs(l.score() - 0.5) < 1e-9;
        }
        verifier(a && b && ind, "liens A, B et indirect");
        Table tb = Table.depuis(c.formes);
        verifier(tb.get("bois") != null && tb.get("bois")[0].equals("bois") && tb.get("bois")[1].equals("NOUN"), "table de lemmes");
        Texte tx = new Texte(tb, "vert", 5);
        tx.phrase("Le bois vert brûle lentement dans la cheminée");
        tx.phrase("Le bois vert brûle lentement");
        List<Lien> lt = tx.liens(2, 10);
        verifier(lt.stream().anyMatch(l -> l.voisin().equals("bois")) && lt.stream().noneMatch(l -> l.voisin().equals("le"))
                && lt.stream().noneMatch(l -> l.voisin().equals("cheminée")), "collocations du texte brut");
        System.out.println("selftest OK");
    }

    // ---------------------------------------------------------------- texte brut

    static final Pattern MOT = Pattern.compile("\\p{L}+");
    static final Set<String> STOP = Set.copyOf(Arrays.asList(("le la les un une des du de au aux ce cet cette ces mon ton son ma ta sa mes "
            + "tes ses notre votre leur nos vos leurs je tu il elle on nous vous ils elles me te se lui eux moi toi soi y en qui que quoi "
            + "dont où et ou mais donc or ni car ne pas plus si comme dans sur sous avec sans pour par entre vers chez contre depuis "
            + "pendant avant après très aussi bien être avoir est sont était étaient sera ont avait avaient été fut suis es sommes êtes "
            + "ai as avons avez ça cela ceci celui celle ceux celles tout tous toute toutes même autre autres alors ainsi lors puis "
            + "encore déjà jamais toujours souvent peu trop assez").split(" ")));

    /** Forme -> (lemme, catégorie dominante), apprise sur les corpus CoNLL-U. */
    static final class Table {
        private final Map<String, String[]> best = new HashMap<>();

        static Table depuis(Map<String, Map<String, Integer>> formes) {
            Table t = new Table();
            for (Map.Entry<String, Map<String, Integer>> e : formes.entrySet()) {
                String meilleur = e.getValue().entrySet().stream()
                        .max(Map.Entry.<String, Integer>comparingByValue().thenComparing(Map.Entry::getKey)).get().getKey();
                t.best.put(e.getKey(), meilleur.split("\\|", 2));
            }
            return t;
        }

        String[] get(String forme) {
            return best.get(forme);
        }
    }

    /** Collocations calculées sur du texte brut (fenêtre de mots pleins, score NPMI). */
    static final class Texte {
        final Table table;
        final String cible;
        final int fenetre;
        final Set<String> variantes;
        final Map<String, Integer> lemmes = new HashMap<>();
        final Map<String, Integer> coll = new HashMap<>();
        long n;

        Texte(Table table, String cible, int fenetre) {
            this.table = table;
            this.cible = cible;
            this.fenetre = fenetre;
            this.variantes = Set.of(cible, cible + "e", cible + "s", cible + "es");
        }

        void phrase(String s) {
            List<String> mots = new ArrayList<>();
            Matcher m = MOT.matcher(s.toLowerCase(Locale.ROOT));
            while (m.find()) {
                String f = m.group();
                String[] lp = table.get(f);
                String lemme = lp != null ? lp[0] : (variantes.contains(f) ? cible : f);
                if (!lemme.equals(cible)) {
                    if (lemme.length() < 3 || STOP.contains(lemme) || STOP.contains(f)) {
                        continue;
                    }
                    if (lp != null && !CONTENU.contains(lp[1])) {
                        continue;
                    }
                }
                mots.add(lemme);
            }
            for (String w : mots) {
                lemmes.merge(w, 1, Integer::sum);
                n++;
            }
            for (int k = 0; k < mots.size(); k++) {
                if (!mots.get(k).equals(cible)) {
                    continue;
                }
                for (int j = Math.max(0, k - fenetre); j < Math.min(mots.size(), k + fenetre + 1); j++) {
                    if (j != k && !mots.get(j).equals(cible)) {
                        coll.merge(mots.get(j), 1, Integer::sum);
                    }
                }
            }
        }

        void lire(Path f) throws IOException {
            try (Stream<String> lignes = Files.lines(f, StandardCharsets.UTF_8)) {
                lignes.forEach(l -> {
                    for (String ph : l.split("[.!?;:…]+")) {
                        if (!ph.isBlank()) {
                            phrase(ph);
                        }
                    }
                });
            }
        }

        List<Lien> liens(int minCount, int top) {
            long u = lemmes.getOrDefault(cible, 0);
            List<Lien> b = new ArrayList<>();
            for (Map.Entry<String, Integer> e : coll.entrySet()) {
                double s = e.getValue() < minCount ? 0 : npmi(e.getValue(), u, lemmes.getOrDefault(e.getKey(), 0), n);
                if (s > 0) {
                    b.add(new Lien("B", "Collocation", e.getKey(), s));
                }
            }
            b.sort(Comparator.comparingDouble(Lien::score).reversed());
            return new ArrayList<>(b.subList(0, Math.min(top, b.size())));
        }
    }

    static List<Path> fichiersTexte(List<String> chemins) throws IOException {
        List<Path> out = new ArrayList<>();
        for (String c : chemins) {
            Path p = Path.of(c);
            if (Files.isDirectory(p)) {
                try (Stream<Path> s = Files.walk(p)) {
                    s.filter(f -> f.toString().endsWith(".txt")).sorted().forEach(out::add);
                }
            } else {
                out.add(p);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- Wikipédia (téléchargement automatique)

    /** Télécharge en texte brut les n articles les plus pertinents pour le mot ; les fichiers déjà présents sont réutilisés. */
    static List<Path> wikipedia(String mot, int n, Path dossier, String base) throws IOException, InterruptedException {
        Files.createDirectories(dossier);
        HttpClient http = fr.ivorama.cognitive.WebHttpClientFactory.create(System.getenv());
        List<String> titres = new ArrayList<>();
        int offset = 0;
        while (titres.size() < n) {
            String url = base + "?action=query&list=search&format=json&formatversion=2&srlimit=" + Math.min(50, n - titres.size())
                    + "&sroffset=" + offset + "&srsearch=" + enc(mot);
            Map<String, Object> racine = objet(Json.parse(get(http, url)));
            Map<String, Object> q = objet(racine.get("query"));
            List<?> res = q == null ? List.of() : (List<?>) q.get("search");
            if (res == null || res.isEmpty()) {
                break;
            }
            for (Object o : res) {
                titres.add((String) objet(o).get("title"));
            }
            Map<String, Object> suite = objet(racine.get("continue"));
            if (suite == null || !(suite.get("sroffset") instanceof Number sn)) {
                break;
            }
            offset = sn.intValue();
        }
        List<Path> out = new ArrayList<>();
        int k = 0;
        for (String titre : titres) {
            k++;
            Path f = dossier.resolve(String.format(Locale.ROOT, "%03d_%s.txt", k, nomSur(titre)));
            if (!Files.exists(f)) {
                String url = base + "?action=query&prop=extracts&explaintext=1&redirects=1&format=json&formatversion=2&titles=" + enc(titre);
                Map<String, Object> q = objet(objet(Json.parse(get(http, url))).get("query"));
                Object pages = q == null ? null : q.get("pages");
                Iterable<?> liste = pages instanceof Map<?, ?> mp ? mp.values() : pages instanceof List<?> l ? l : List.of();
                String texte = "";
                for (Object pg : liste) {
                    if (objet(pg).get("extract") instanceof String ex) {
                        texte = ex;
                    }
                }
                if (texte.isBlank()) {
                    continue;
                }
                Files.writeString(f, texte, StandardCharsets.UTF_8);
                Thread.sleep(150);
            }
            out.add(f);
        }
        return out;
    }

    /** Nom de fichier ASCII (certains systèmes n'acceptent pas les accents dans les noms). */
    private static String nomSur(String titre) {
        String t = java.text.Normalizer.normalize(titre, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return t.replaceAll("[^A-Za-z0-9]+", "_");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objet(Object o) {
        return o instanceof Map<?, ?> ? (Map<String, Object>) o : null;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String get(HttpClient http, String url) throws IOException, InterruptedException {
        HttpRequest rq = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                .header("User-Agent", "ExtracteurVoisins/1.0 (usage personnel, Java)").GET().build();
        HttpResponse<String> r = http.send(rq, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (r.statusCode() != 200) {
            throw new IOException("HTTP " + r.statusCode() + " pour " + url);
        }
        return r.body();
    }

    /** Analyseur JSON minimal (objets, tableaux, chaînes, nombres, booléens, null). */
    static final class Json {
        private final String s;
        private int i;

        private Json(String s) {
            this.s = s;
        }

        static Object parse(String s) {
            Json p = new Json(s);
            p.ws();
            Object v = p.val();
            p.ws();
            if (p.i != s.length()) {
                throw p.err("contenu en trop");
            }
            return v;
        }

        private RuntimeException err(String m) {
            return new IllegalArgumentException("JSON invalide à la position " + i + " : " + m);
        }

        private void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        private void expect(char c) {
            if (i >= s.length() || s.charAt(i) != c) {
                throw err("« " + c + " » attendu");
            }
            i++;
        }

        private Object val() {
            if (i >= s.length()) {
                throw err("fin inattendue");
            }
            char c = s.charAt(i);
            if (c == '{') {
                return obj();
            }
            if (c == '[') {
                return arr();
            }
            if (c == '"') {
                return str();
            }
            if (s.startsWith("true", i)) {
                i += 4;
                return Boolean.TRUE;
            }
            if (s.startsWith("false", i)) {
                i += 5;
                return Boolean.FALSE;
            }
            if (s.startsWith("null", i)) {
                i += 4;
                return null;
            }
            return num();
        }

        private Map<String, Object> obj() {
            Map<String, Object> m = new LinkedHashMap<>();
            expect('{');
            ws();
            if (i < s.length() && s.charAt(i) == '}') {
                i++;
                return m;
            }
            while (true) {
                ws();
                String k = str();
                ws();
                expect(':');
                ws();
                m.put(k, val());
                ws();
                if (i < s.length() && s.charAt(i) == ',') {
                    i++;
                    continue;
                }
                expect('}');
                return m;
            }
        }

        private List<Object> arr() {
            List<Object> l = new ArrayList<>();
            expect('[');
            ws();
            if (i < s.length() && s.charAt(i) == ']') {
                i++;
                return l;
            }
            while (true) {
                ws();
                l.add(val());
                ws();
                if (i < s.length() && s.charAt(i) == ',') {
                    i++;
                    continue;
                }
                expect(']');
                return l;
            }
        }

        private String str() {
            expect('"');
            StringBuilder b = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') {
                    return b.toString();
                }
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n' -> b.append('\n');
                        case 't' -> b.append('\t');
                        case 'r' -> b.append('\r');
                        case 'b' -> b.append('\b');
                        case 'f' -> b.append('\f');
                        case 'u' -> {
                            b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                        }
                        default -> b.append(e);
                    }
                } else {
                    b.append(c);
                }
            }
            throw err("chaîne non terminée");
        }

        private Number num() {
            int debut = i;
            while (i < s.length() && "+-.eE0123456789".indexOf(s.charAt(i)) >= 0) {
                i++;
            }
            if (debut == i) {
                throw err("valeur inattendue");
            }
            return Double.valueOf(s.substring(debut, i));
        }
    }

    public static void main(String[] args) throws Exception {
        String mot = null;
        List<String> conllu = new ArrayList<>();
        List<String> textes = new ArrayList<>();
        Path lex = null;
        Path sortie = Path.of("voisins.csv");
        int fenetre = 5;
        int minCount = 2;
        int top = 150;
        int distance = 2;
        int wiki = 0;
        double decay = 0.5;
        String wikiUrl = "https://fr.wikipedia.org/w/api.php";
        for (int k = 0; k < args.length; k++) {
            switch (args[k]) {
                case "--selftest" -> {
                    selftest();
                    return;
                }
                case "--conllu" -> {
                    while (k + 1 < args.length && !args[k + 1].startsWith("--")) {
                        conllu.add(args[++k]);
                    }
                }
                case "--texte" -> {
                    while (k + 1 < args.length && !args[k + 1].startsWith("--")) {
                        textes.add(args[++k]);
                    }
                }
                case "--wikipedia" -> wiki = Integer.parseInt(args[++k]);
                case "--wikipedia-url" -> wikiUrl = args[++k];
                case "--lexique" -> lex = Path.of(args[++k]);
                case "--sortie" -> sortie = Path.of(args[++k]);
                case "--fenetre" -> fenetre = Integer.parseInt(args[++k]);
                case "--min-count" -> minCount = Integer.parseInt(args[++k]);
                case "--top" -> top = Integer.parseInt(args[++k]);
                case "--distance" -> distance = Integer.parseInt(args[++k]);
                case "--decay" -> decay = Double.parseDouble(args[++k]);
                default -> mot = args[k];
            }
        }
        if (mot == null || (conllu.isEmpty() && textes.isEmpty() && wiki == 0)) {
            System.err.println("Usage : java ExtracteurVoisins.java mot [--conllu f_ou_dossier] [--texte f_ou_dossier] "
                    + "[--wikipedia N] [--lexique f.csv] [--sortie voisins.csv] [--top 150]");
            return;
        }
        if(fenetre<1||fenetre>100||minCount<1||top<1||top>10000||distance<1||distance>20||wiki<0||wiki>100||!Double.isFinite(decay)||decay<0||decay>1)throw new IllegalArgumentException("Paramètres d’extraction hors limites");
        String cible = mot.toLowerCase(Locale.ROOT);

        Compteurs cpt = new Compteurs();
        for (Path f : fichiers(conllu)) {
            for (List<Tok> phrase : lirePhrases(Files.readAllLines(f, StandardCharsets.UTF_8))) {
                cpt.ajouter(phrase, cible, fenetre, distance);
            }
        }
        int occ = cpt.lemmes.getOrDefault(cible, 0);
        List<Lien> liens = occ > 0 ? cpt.liens(cible, minCount, top, decay) : new ArrayList<>();

        int occTexte = 0;
        if (!textes.isEmpty() || wiki > 0) {
            List<Path> fichiersTxt = new ArrayList<>(fichiersTexte(textes));
            if (wiki > 0) {
                fichiersTxt.addAll(wikipedia(cible, wiki, Path.of("wikipedia_" + cible), wikiUrl));
            }
            Texte t = new Texte(Table.depuis(cpt.formes), cible, fenetre);
            for (Path f : fichiersTxt) {
                t.lire(f);
            }
            liens.removeIf(l -> l.liste().equals("B")); // collocations du texte brut : bien plus de données
            liens.addAll(t.liens(minCount, top));
            occTexte = t.lemmes.getOrDefault(cible, 0);
            System.out.printf(Locale.ROOT, "%d fichiers texte lus, %d mots retenus.%n", fichiersTxt.size(), t.n);
        }
        if (occ + occTexte == 0) {
            System.err.println("« " + cible + " » n'apparaît pas dans les corpus.");
            return;
        }
        List<String> lx = lex == null ? List.of() : lexique(lex, cible);
        ecrire(sortie, cible, liens, lx);
        System.out.printf(Locale.ROOT, "%d occurrences (CoNLL-U), %d (texte), %d voisins, %d lignes de lexique -> %s%n",
                occ, occTexte, liens.size(), lx.size(), sortie);
    }
}
