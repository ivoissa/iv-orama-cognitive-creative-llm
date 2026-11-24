# IV-ORAMA

Le projet regroupe Cognitive 0.7.12 P3.4 et French NLP Lite r5. Le YAML commun est `config/application.yml`.

| Réglages | Section |
|---|---|
| Modèle Ollama, URL locale et budgets | `llm` |
| Passerelle de contrôle et références locales aux valeurs protégées | `security` |
| Recherche Wikipédia optionnelle | `tools` |
| Rendu de la réponse en mode `--chat-iv` | `rendering` et `transformations` |
| Fournisseurs lexicaux de la Lite | `synonyms` |
| Banc Lite | `benchmark_enabled` |

Depuis la racine, avec un JDK 21. Les sources LLM font partie de la compilation standard :

```sh
mvn clean install
java -cp "cognitive/target/iv-orama-cognitive-0.7.12-experiment.jar:cognitive/target/lib/*" fr.ivorama.cognitive.generative.LocalAssistantCli --check
java -cp "cognitive/target/iv-orama-cognitive-0.7.12-experiment.jar:cognitive/target/lib/*" fr.ivorama.cognitive.generative.LocalAssistantCli --chat-iv
```

Sous Windows, utiliser `;` comme séparateur du classpath. Démarrer Ollama et installer le modèle indiqué dans `llm.model` avant `--check`. Un autre YAML est possible via `--config chemin.yml` avant le mode. Les propriétés JVM existantes restent prioritaires sur le YAML.

Le rendu Lite est optionnel. Il s’applique localement à la réponse finale en mode `--chat-iv` lorsque `rendering.enabled` vaut `true`. Le réglage par défaut est `false`. Le rendu examine chaque ligne et conserve les sauts de ligne et les blocs de code balisés. La console ajoute `**[Lite / règles appliquées]**` aux lignes réécrites. Le quota d’emphase s’applique à chaque ligne traitée.

Le YAML active le contrôle entrée/sortie `ENFORCE` existant. La construction d’une version expurgée des résultats d’outils et la table locale d’alias restent à développer. Le fichier facultatif `security.protected_values_file` doit rester hors de l’archive et du dépôt.

La Lite produit du bytecode Java 17. Pour le banc, activer `benchmark_enabled` et lancer `com.ivorama.nlp.MainApp` depuis la racine ou le dossier `french-nlp-lite`.

## Fournisseur OpenAI ou compatible

Pour GPT-5.1, modifier les quatre clés suivantes dans la section `llm` du YAML :

```yaml
llm:
  provider: openai
  endpoint: "https://api.openai.com/v1"
  model: "gpt-5.1"
  api_key_env: OPENAI_API_KEY
```

Les autres clés peuvent rester dans la section. Les paramètres `threads`, `gpu_layers`, `context_tokens` et `temperature` servent à Ollama. Pour un autre fournisseur qui expose l’API Chat Completions, choisir `openai-compatible` avec son URL de base et son identifiant de modèle. Les modes disponibles pour ces fournisseurs sont `--chat` et `--chat-iv`. Les modes spécialisés Ollama continuent avec `provider: ollama`.

Définir la variable désignée par `api_key_env` dans l’environnement de lancement. Garder `security.mode: ENFORCE` pour cette connexion. La clé sert à l’en-tête d’authentification et reste hors du YAML et du texte envoyé au modèle. Les résultats d’outils structurés et la détection exhaustive de secrets restent hors du périmètre de cette version.
