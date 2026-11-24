# Provenance et licences

- Stanford CoreNLP 4.5.8 + models-french et JWI 2.4.0 : artefacts Maven d'origine, licences/notices conservées dans leurs JAR. Aucune modification de ces bibliothèques. Le modèle français CoreNLP est restauré par Maven.
- OMW français / WOLF : même donnée française alignée, pas deux votes indépendants. CeCILL-C fournie sous src/main/resources/lexicon/OMW-fra-LICENSE.html. Ressource officielle et sens des annotations ManVal/ManAdd : https://almanach.inria.fr/software_and_resources/WOLF-fr.html (consultée pendant la mission). Une annotation manuelle atteste un couple lexical ; elle ne prouve pas le sens en contexte.
- Gloses WordNet 3.0 et passerelle OMW : ressources déjà présentes dans la base, utilisées sans inventer de relations. Attribution Princeton WordNet : https://wordnet.princeton.edu/ .
- Morphologie : données Dicollecte/Grammalecte restaurées depuis les sources French NLP RC5.8, elles-mêmes accompagnées de Grammalecte 2.3.0 et de la GPL 3. Le TSV et son empreinte sont fournis ; les notices amont sont conservées sous third-party/grammalecte. Aucun code Python n'est exécuté pour lire ces données.
- Robert : TSV privé de la RC6.21, provenance page/ligne/raw conservée. Aucun droit de redistribution publique vérifiable n'a été fourni. Aucune publication effectuée, aucune autorisation de redistribution ne peut être déduite de cette livraison privée.
- Export JWI : sérialisation lexicale française de l'OMW fourni, mêmes conditions que la source, pas une nouvelle taxonomie. Le fichier EXPORT.properties indique pointers=0 et les exclusions sont tracées.
- Jakarta JSON-P 1.1.6 : dépendance de test/rapport, pas du moteur de production. notices d’origine conservées ; aucune analyse NLP n'y est déléguée.

Cette liste décrit les ressources effectivement fournies. Elle n'accorde pas de droits supplémentaires sur le dictionnaire Robert ni d'autorisation de publier l'ensemble sous une licence globale.

La ressource dérivée `animate-nouns.tsv` provient de WOLF : lectures nominales manuellement validées lorsqu'elles existent, sinon lectures `gwa2012 >= 0.9`, et ascendance hyperonymique vers les concepts WordNet 3.0 personne/animal. Le seuil est un filtre de provenance heuristique, pas une probabilité de correction linguistique. Générateur Java : `tools/validation/BuildAnimateLexicon.java`, empreinte vérifiée au chargement. L'interprétation de la marque de transitivité indirecte suit le champ Vtyp de Grammalecte 2.3.0 (`grammalecte/fr/conj.py`, fonction `_readableInfo`), source amont conservée dans l'archive distribuée. Aucun code Python amont n'est exécuté.

Maven récupère JUnit Jupiter et exécute les tests avec Surefire.

L’installateur restaure DISCO API 3.0.0 et le modèle français officiel sans modifier les binaires amont. Attribution, liens éditeur, licences annoncées et empreintes figurent dans `third-party/disco/NOTICE.txt`. Le modèle n'est pas un LLM ; les calculs et l'assemblage sont réalisés en Java 17. Le profil utilisant cet index nécessite les options Java fournies dans les lanceurs ; le profil normal n'en dépend pas.
