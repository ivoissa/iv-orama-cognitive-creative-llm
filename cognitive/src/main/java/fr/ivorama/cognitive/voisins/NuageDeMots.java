package fr.ivorama.cognitive.voisins;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Nuage de mots pondéré à partir de A.json (relations syntaxiques) et B.json (relations lexicales).
 *
 * Lancement (Java 17+, un seul fichier) :
 *   java NuageDeMots.java [mot] [A.json] [B.json] [voisins.csv] [mu] [gamma] [lambda]
 *
 * Modèle :
 *   alpha(r)        = lambda * rang_global(r)^(-gamma) + (1 - lambda) * proximite(r)
 *                     (lambda = 0 : proximité seule, valeur par défaut ; lambda = 1 : rang seul)
 *   w_liste(v,sens) = max sur les relations r de alpha(r) * score(u,v,r)
 *   w(v,sens)       = mu * w_A + (1 - mu) * w_B
 *   W(v)            = somme sur les sens de P(sens) * w(v,sens), puis normalisé par le maximum
 * Un sens noté « * » s'applique à tous les sens (probabilité 1), par exemple l'homonymie.
 */
public final class NuageDeMots {

    record Ligne(String sens, double ps, String voisin, String liste, String relation, double score) {}

    record Resultat(String voisin, double poids, List<String> details) {}

    public static void main(String[] args) throws IOException {
        String mot = args.length > 0 ? args[0] : "vert";
        Path pA = Path.of(args.length > 1 ? args[1] : "A.json");
        Path pB = Path.of(args.length > 2 ? args[2] : "B.json");
        Path pV = Path.of(args.length > 3 ? args[3] : "voisins.csv");
        double mu = args.length > 4 ? Double.parseDouble(args[4]) : 0.5;
        double gamma = args.length > 5 ? Double.parseDouble(args[5]) : 1.0;
        double lambda = args.length > 6 ? Double.parseDouble(args[6]) : 0.0;
        if (!Double.isFinite(mu) || mu < 0 || mu > 1) {
            throw new IllegalArgumentException("mu doit être dans [0, 1]");
        }
        if (!Double.isFinite(lambda) || lambda < 0 || lambda > 1) {
            throw new IllegalArgumentException("lambda doit être dans [0, 1]");
        }
        if (!Double.isFinite(gamma) || gamma <= 0) {
            throw new IllegalArgumentException("gamma doit être strictement positif");
        }

        Referentiel ra = args.length > 1 ? Referentiel.charger(pA) : Referentiel.ressource("A.json");
        Referentiel rb = args.length > 2 ? Referentiel.charger(pB) : Referentiel.ressource("B.json");
        List<Ligne> lignes = lire(pV, mot);
        if (lignes.isEmpty()) {
            System.err.println("Aucun voisin trouvé pour « " + mot + " » dans " + pV);
            return;
        }
        List<Resultat> res = calculer(lignes, ra, rb, mu, gamma, lambda);
        afficher(mot, res, mu, gamma, lambda);

        Path html = Path.of("nuage_" + mot.replaceAll("[^A-Za-z0-9_-]", "_") + ".html");
        Files.writeString(html, html(mot, res), StandardCharsets.UTF_8);
        System.out.println("\nNuage écrit dans " + html.toAbsolutePath());
    }

    static List<Resultat> calculer(List<Ligne> lignes, Referentiel ra, Referentiel rb, double mu, double gamma, double lambda) {
        if(!Double.isFinite(mu)||mu<0||mu>1||!Double.isFinite(gamma)||gamma<=0||!Double.isFinite(lambda)||lambda<0||lambda>1)throw new IllegalArgumentException("Pondérations invalides");
        Map<String, Double> ps = new LinkedHashMap<>();
        for (Ligne l : lignes) {
            if (!l.sens().equals("*")) {
                Double ancien = ps.put(l.sens(), l.ps());
                if (ancien != null && Math.abs(ancien - l.ps()) > 1e-9) {
                    System.err.println("Avertissement : probabilités différentes pour le sens « " + l.sens() + " »");
                }
            }
        }
        double somme = ps.values().stream().mapToDouble(Double::doubleValue).sum();
        if (!ps.isEmpty() && Math.abs(somme - 1) > 1e-6) {
            System.err.printf(Locale.ROOT, "Avertissement : la somme des probabilités de sens vaut %.3f (attendu 1).%n", somme);
        }

        Map<String, Map<String, double[]>> parVoisin = new LinkedHashMap<>();
        Map<String, List<String>> details = new LinkedHashMap<>();
        for (Ligne l : lignes) {
            boolean estA = l.liste().equals("A");
            Referentiel r = estA ? ra : rb;
            Integer g = r.rang(l.relation());
            if (g == null) {
                System.err.println("Relation inconnue ignorée : " + l.relation() + " (liste " + l.liste() + ")");
                continue;
            }
            double alpha = lambda * Math.pow(g, -gamma);
            if (lambda < 1) {
                Double px = r.proximite(l.relation());
                if (px == null) {
                    throw new IllegalArgumentException("Champ proximite absent pour la relation « " + l.relation() + " »");
                }
                alpha += (1 - lambda) * px;
            }
            double w = alpha * l.score();
            double[] t = parVoisin
                    .computeIfAbsent(l.voisin(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(l.sens(), k -> new double[2]);
            int idx = estA ? 0 : 1;
            t[idx] = Math.max(t[idx], w);
            details.computeIfAbsent(l.voisin(), k -> new ArrayList<>())
                    .add(String.format(Locale.ROOT, "%s [%s, rang %d, score %.2f]", l.relation(), l.liste(), g, l.score()));
        }

        Map<String, Double> brut = new LinkedHashMap<>();
        double max = 0;
        for (Map.Entry<String, Map<String, double[]>> e : parVoisin.entrySet()) {
            double total = 0;
            for (Map.Entry<String, double[]> s : e.getValue().entrySet()) {
                double p = s.getKey().equals("*") ? 1.0 : ps.getOrDefault(s.getKey(), 0.0);
                total += p * (mu * s.getValue()[0] + (1 - mu) * s.getValue()[1]);
            }
            brut.put(e.getKey(), total);
            max = Math.max(max, total);
        }

        List<Resultat> out = new ArrayList<>();
        for (Map.Entry<String, Double> e : brut.entrySet()) {
            out.add(new Resultat(e.getKey(), max > 0 ? e.getValue() / max : 0, details.get(e.getKey())));
        }
        out.sort(Comparator.comparingDouble(Resultat::poids).reversed());
        return out;
    }

    static List<Ligne> lire(Path p, String mot) throws IOException {
        List<Ligne> out = new ArrayList<>();
        int n = 0;
        for (String brut : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            n++;
            String s = brut.strip();
            if (s.isEmpty() || s.startsWith("#")) {
                continue;
            }
            String[] c = s.split(";", -1);
            if (c.length != 7) {
                throw new IllegalArgumentException("Ligne " + n + " : 7 colonnes attendues, " + c.length + " trouvées");
            }
            if (c[0].strip().equals("mot") || !c[0].strip().equalsIgnoreCase(mot)) {
                continue;
            }
            String liste = c[4].strip().toUpperCase(Locale.ROOT);
            if (!liste.equals("A") && !liste.equals("B")) {
                throw new IllegalArgumentException("Ligne " + n + " : liste doit valoir A ou B");
            }
            double ps = Double.parseDouble(c[2].strip().replace(',', '.'));
            double score = Double.parseDouble(c[6].strip().replace(',', '.'));
            if (!Double.isFinite(ps) || !Double.isFinite(score) || ps < 0 || ps > 1 || score < 0 || score > 1) {
                throw new IllegalArgumentException("Ligne " + n + " : probabilité et score doivent être dans [0, 1]");
            }
            out.add(new Ligne(c[1].strip(), ps, c[3].strip(), liste, c[5].strip(), score));
        }
        return out;
    }

    static void afficher(String mot, List<Resultat> res, double mu, double gamma, double lambda) {
        System.out.printf(Locale.ROOT, "Voisins de « %s » (mu = %.2f, gamma = %.2f, lambda = %.2f)%n", mot, mu, gamma, lambda);
        System.out.printf(Locale.ROOT, "%-4s %-14s %-7s %s%n", "N°", "Voisin", "Poids", "Relations");
        int i = 1;
        for (Resultat r : res) {
            System.out.printf(Locale.ROOT, "%-4d %-14s %-7.3f %s%n", i++, r.voisin(), r.poids(), String.join(" ; ", r.details()));
        }
    }

    static String html(String mot, List<Resultat> res) {
        List<Resultat> tri = new ArrayList<>(res);
        tri.sort(Comparator.comparing(Resultat::voisin, String.CASE_INSENSITIVE_ORDER));
        StringBuilder b = new StringBuilder();
        b.append("<!doctype html>\n<html lang=\"fr\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<title>Nuage – ").append(esc(mot)).append("</title>")
                .append("<style>:root{color-scheme:light dark}")
                .append("body{font-family:system-ui,sans-serif;margin:1rem;text-align:center;line-height:1.3}")
                .append("span{display:inline-block;margin:.15em .4em}</style></head><body>")
                .append("<h1>").append(esc(mot)).append("</h1><p>");
        for (Resultat r : tri) {
            double taille = 0.9 + 3.1 * r.poids();
            double opacite = 0.45 + 0.55 * r.poids();
            b.append(String.format(Locale.ROOT,
                    "<span style=\"font-size:%.2frem;opacity:%.2f\" title=\"%s\">%s</span>%n",
                    taille, opacite, esc(String.format(Locale.ROOT, "poids %.3f : ", r.poids()) + String.join(" ; ", r.details())),
                    esc(r.voisin())));
        }
        b.append("</p></body></html>\n");
        return b.toString();
    }

    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** Rang global de chaque relation d'un fichier JSON (catégories puis relations, dans l'ordre des rangs). */
    static final class Referentiel {
        private final Map<String, Integer> rangs = new LinkedHashMap<>();
        private final Map<String, Double> proximites = new LinkedHashMap<>();

        Double proximite(String nom) {
            return proximites.get(norm(nom));
        }

        Integer rang(String nom) {
            return rangs.get(norm(nom));
        }

        @SuppressWarnings("unchecked")
        static Referentiel charger(Path p) throws IOException {
            return chargerTexte(Files.readString(p, StandardCharsets.UTF_8));
        }

        static Referentiel ressource(String nom) throws IOException {
            try(var in=NuageDeMots.class.getResourceAsStream(nom)){
                if(in==null)throw new IOException("Référentiel embarqué absent : "+nom);
                return chargerTexte(new String(in.readAllBytes(),StandardCharsets.UTF_8));
            }
        }

        @SuppressWarnings("unchecked")
        static Referentiel chargerTexte(String texte) {
            Object racine = Json.parse(texte);
            Map<String, Object> m = (Map<String, Object>) racine;
            List<Map<String, Object>> cats = new ArrayList<>();
            for (Object o : (List<Object>) m.get("categories")) {
                cats.add((Map<String, Object>) o);
            }
            cats.sort(Comparator.comparingDouble(c -> ((Number) c.get("rang")).doubleValue()));
            Referentiel r = new Referentiel();
            int g = 0;
            for (Map<String, Object> c : cats) {
                List<Map<String, Object>> rels = new ArrayList<>();
                for (Object o : (List<Object>) c.get("relations")) {
                    rels.add((Map<String, Object>) o);
                }
                rels.sort(Comparator.comparingDouble(x -> ((Number) x.get("rang")).doubleValue()));
                for (Map<String, Object> x : rels) {
                    g++;
                    String cle = norm((String) x.get("nom"));
                    r.rangs.putIfAbsent(cle, g);
                    if (x.get("proximite") instanceof Number px) {
                        r.proximites.putIfAbsent(cle, px.doubleValue());
                    }
                }
            }
            return r;
        }

        static String norm(String s) {
            String t = s.replaceAll("\\(.*?\\)", "").strip().toLowerCase(Locale.ROOT);
            t = Normalizer.normalize(t, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
            return t.replace('’', '\'').replaceAll("\\s+", " ");
        }
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
}
