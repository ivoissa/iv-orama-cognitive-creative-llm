package fr.ivorama.cognitive;

import java.util.*;

/**
 * Grammaire de référence du correcteur, indépendante du parseur français.
 * atom, not(expr), and(expr,expr), implies(expr,expr), bottom.
 * Equivalence propositionnelle calculée par énumération de valuations (au plus 10 atomes).
 */
public final class OracleFormula {
    private OracleFormula() {}
    public static Formula parse(String expression) {
        Parser p = new Parser(Objects.requireNonNull(expression));
        Formula f=p.formula();
        p.space();
        if(p.position!=p.source.length()) throw new IllegalArgumentException("Oracle : suffixe inattendu");
        return f;
    }
    public static boolean equivalent(Formula a,Formula b) {
        SortedSet<String> names=new TreeSet<>(Formula.atoms(a));
        names.addAll(Formula.atoms(b));
        if(names.size()>10) throw new IllegalArgumentException("Oracle : plus de 10 atomes ; ne pas déclarer une réussite");
        List<String> atoms=new ArrayList<>(names);
        for(int mask=0;mask<(1<<atoms.size());mask++) {
            Map<String,Boolean> values=new HashMap<>();
            for(int i=0;i<atoms.size();i++) values.put(atoms.get(i),(mask&(1<<i))!=0);
            if(a.evaluate(values)!=b.evaluate(values)) return false;
        }
        return true;
    }
    private static final class Parser {
        final String source;
        int position;
        Parser(String source){this.source=source;}
        void space(){while(position<source.length() && Character.isWhitespace(source.charAt(position)))position++;}
        boolean consume(String s){space(); if(source.startsWith(s,position)){position+=s.length();return true;}return false;}
        void expect(String s){if(!consume(s))throw new IllegalArgumentException("Oracle : attendu '"+s+"' à "+position);}
        String identifier(){space();int start=position;
            while(position<source.length() && (Character.isLetterOrDigit(source.charAt(position)) || source.charAt(position)=='_'))position++;
            if(start==position)throw new IllegalArgumentException("Oracle : identifiant attendu à "+position);
            return source.substring(start,position);
        }
        Formula formula(){space();
            if(consume("not(")){Formula f=formula();expect(")");return Formula.not(f);}
            if(consume("and(")){Formula a=formula();expect(",");Formula b=formula();expect(")");return Formula.and(a,b);}
            if(consume("implies(")){Formula a=formula();expect(",");Formula b=formula();expect(")");return Formula.implies(a,b);}
            if(consume("bottom"))return Formula.Bottom.INSTANCE;
            if(consume("atom(")){String name=identifier();expect(")");return Formula.atom(name);}
            return Formula.atom(identifier());
        }
    }
}
