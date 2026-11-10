# Séance 9 — Les questions en langage naturel (GraphRAG)

**Objectif :** comprendre comment la plateforme répond à une question en français ou en anglais **sans faire confiance au modèle**, et être capable de défendre ses garde-fous, y compris leurs limites.

**Durée :** 1 h 30 · **Prérequis :** séances 4 et 8.

C'est le plus petit bloc du projet (environ 430 lignes), donc le plus facile à maîtriser entièrement. Fichiers :
- `graphrag/app/main.py` (73) : l'API FastAPI.
- `graphrag/app/qa.py` (206) : le tri, la requête, la réponse.
- `graphrag/app/guard.py` (55) : les garde-fous sur le Cypher.
- `graphrag/app/schema.py` (69) : ce que le modèle sait du graphe.
- `graphrag/app/config.py` (27) : la configuration.
- Côté serveur : `backend/.../qa/QaService.java`, `qa/QaClient`, `controller/QaController.java`, `resources/help/app-guide.md` (30 lignes).

---

## 1. Qu'est-ce que GraphRAG ?

- **RAG** (*Retrieval-Augmented Generation*) : au lieu de laisser le modèle répondre de mémoire, on **lui donne les informations pertinentes** avec la question.
- **GraphRAG** : ces informations viennent d'un **graphe**. Le modèle écrit une requête Cypher, on l'exécute, et il **rédige la réponse à partir des seules lignes obtenues**.

Dans ce projet, on y ajoute deux autres sources pour deux autres types de questions (§3).

---

## 2. Le parcours complet d'une question

```
Navigateur ──► Serveur Spring Boot ──► Service de questions (FastAPI) ──► Ollama + Neo4j
  question       QaService.ask             Answerer.ask
                 + faits de qualité        1. trier   2. Cypher   3. répondre
```

### Côté serveur : `QaService.ask`
1. Refuse une question **vide** ou de plus de **1 000 caractères**.
2. Charge le **dernier résultat de construction** du graphe (`graph.last`) : il est **facultatif**.
3. Rassemble les **faits** (`facts`) et appelle le service avec la question, le **périmètre** (`GraphService.scope`, par exemple `d2v2`) et ces faits.

Les faits envoyés :

| Clé | Contenu |
| --- | --- |
| `dataset`, `version` | nom du jeu, numéro |
| `context` | domaine, objectif, grain de ligne |
| `rules` | pour chaque règle : description, statut, sévérité, **message** |
| `dimensions` | les 4 dimensions avec pourcentage, numérateur, dénominateur, base |
| `findings` | les constats de la construction du graphe (conflits, orphelines…) |
| `columns` | pour chaque colonne (150 au plus) : table, colonne, **rôle**, % de manquants |
| `graph` | le **schéma** et les effectifs du graphe, s'il est construit |
| `guide` | le texte de `app-guide.md` |

**Pourquoi le serveur les rassemble :** les résultats de qualité vivent **dans le serveur**, pas dans le graphe. Avec eux, le modèle peut expliquer une règle en échec avec **son propre message et ses exemples**, ce qu'un graphe seul ne saurait pas faire (commentaire de la classe).

### Côté service : `main.py`
- `POST /ask` exige l'en-tête **`X-QA-Token`**, comparé avec `hmac.compare_digest` (temps constant, comme en séance 0). `GET /health` est public.
- `AskRequest` valide : question de 1 à 1 000 caractères ; `scope` conforme à `^d\d+v\d+$` ; `facts` libre.
- Les erreurs sont **traduites** : Neo4j inaccessible → **503** ; `ValueError` → **400** ; modèle injoignable, lent ou en refus → **503** « The language model could not answer » (le service ne plante pas).
- Un seul **pilote Neo4j** pour tout le service ; le client du modèle est reconstruit à chaque question avec : température **0,1**, contexte **16 384**, sortie **1 500** jetons, `think = false`.
- Sans `OLLAMA_MODEL`, **503** avec un message explicite.

---

## 3. Étape 1 : trier la question (`_route`, `qa.py`)

Le modèle reçoit `ROUTE_PROMPT` et répond **un seul mot** :

| Mot | Pour des questions sur… | Source de la réponse | Neo4j ? |
| --- | --- | --- | --- |
| `graph` | le **contenu des données** : comptes, quels enregistrements, classements, une valeur précise | requête Cypher | oui |
| `quality` | la **qualité** : règles qui échouent, scores, complétude, valeurs manquantes, constats | faits de qualité | non |
| `help` | l'**application** : comment faire, ce que signifie un onglet ou un score | le guide | non |

Subtilités du code :
- Le code **cherche le premier de ces trois mots** dans la réponse ; si le modèle répond autre chose, le tri vaut **`quality`** par défaut.
- Si le tri donne `graph` mais que **le graphe n'est pas construit**, la question devient `quality` (rien à interroger) : la réponse dira de construire le graphe.
- La distinction **« valeur d'un score dans ce jeu » (qualité) / « ce que signifie un score » (aide)** est écrite dans l'invite.

---

## 4. Étape 2 : écrire et contrôler la requête (`_query`)

Uniquement pour le type `graph`. **Au plus 2 tentatives** (`MAX_ATTEMPTS`) :

1. Construit l'invite `CYPHER_PROMPT` avec : le **périmètre**, le **schéma** (étiquettes, propriétés, relations, effectifs, propriétés techniques `key`, `name`, `_scope`, `_rows`, `__candidates`, `_conflict`), des **exemples** écrits avec les vraies étiquettes du graphe (`schema.examples`), et la question. Règles de l'invite : lecture seule ; **chaque nœud avec `_scope`** ; n'utiliser que les éléments du schéma ; retourner des valeurs lisibles ; comparer le texte sans tenir compte de la casse ; `LIMIT 50` sauf pour un comptage.
2. Appelle le modèle ; `extract_cypher` (bibliothèque `neo4j-graphrag`) extrait la requête de la réponse.
3. **`validate_cypher`** (§5).
4. **`_run`** : demande d'abord à Neo4j `EXPLAIN <requête>` et exige que le type de requête soit `r` (**lecture**) : *« Neo4j says the query is not read-only »* sinon ; puis exécute avec un **délai de 15 s** (`QA_QUERY_TIMEOUT_SECONDS`) en mode lecture. Au plus **50 lignes** lues.
5. En cas de refus (`CypherRejected`, erreur de syntaxe, erreur Neo4j), le **motif est renvoyé au modèle** (« Your previous query was refused: … Write a corrected query. ») pour la 2ᵉ tentative. Les motifs sont gardés dans `warnings`.
6. Si aucune requête admissible n'est trouvée : `cypher = None`, et la réponse **s'appuie sur les faits de qualité seuls** en le disant.

---

## 5. Les garde-fous : `validate_cypher` (guard.py)

*« The model writes the query, so nothing it writes is trusted. »* La fonction, dans l'ordre :

1. Le périmètre est de la forme `d<n>v<n>`.
2. La requête n'est pas vide (on retire un `;` final).
3. **Nettoyage** : les chaînes de caractères et les commentaires sont **neutralisés avant analyse** : un client qui s'appelle « Set » ne déclenche pas le mot interdit `SET`.
4. **Un seul `;` visible** → « Only one statement is allowed ».
5. **Mots interdits** (`_FORBIDDEN`) : `CREATE, MERGE, DELETE, DETACH, SET, REMOVE, DROP, FOREACH, CALL, LOAD, USING, GRANT, DENY, REVOKE, START, STOP, TERMINATE, USE, SHOW, ALTER, RENAME, APOC, DBMS`. Tout ce qui écrit, appelle une procédure, charge, administre.
6. Doit contenir `MATCH` (ou `WITH`, `UNWIND`) **et** `RETURN`.
7. **Périmètre :** chaque littéral de chaîne qui ressemble à un périmètre (`d\d+v\d+`) doit être **celui-ci** (« Only the graph 'd2v2' may be read, not d3v3 »), et ce périmètre doit apparaître comme littéral (« must filter every node on _scope »).
8. **Un filtre par nœud :** on compte les motifs de nœud **étiquetés** `(x:Label` et les occurrences de `_scope` ; il doit y avoir au moins autant de `_scope` que de nœuds étiquetés.

### Essais réels
J'ai passé huit requêtes à `validate_cypher` avec le périmètre `d2v2` :

| Requête | Résultat |
| --- | --- |
| `MATCH (n:Customer {_scope:'d2v2'}) RETURN count(n)` | **autorisée** |
| `… DETACH DELETE n` | refusée : « DETACH is not allowed » |
| `MATCH (n:Customer {_scope:'d3v3'}) …` | refusée : « Only the graph 'd2v2' may be read, not d3v3 » |
| `MATCH (n:Customer) RETURN n.key` | refusée : « must filter every node on _scope » |
| deux instructions séparées par `;` | refusée : « Only one statement is allowed » |
| `CALL db.labels() …` | refusée : « CALL is not allowed » |
| `… WHERE n.name = 'Set' RETURN n.key` | **autorisée** (le mot dans la chaîne est ignoré) |
| `MATCH (n:Customer {_scope:'d2v2'}), (m) RETURN m.key` | **autorisée** — voir ci-dessous |

### Une limite à connaître
La dernière requête **passe** : le nœud `(m)` n'a **pas d'étiquette**, donc il n'est pas compté, et un seul `_scope` suffit pour un seul nœud étiqueté. Une telle requête peut renvoyer des nœuds **d'autres versions**. Ce n'est **pas une écriture** (le garde-fou « lecture seule » et `EXPLAIN` tiennent), et le projet n'a qu'un seul rôle, mais l'affirmation « chaque nœud est filtré sur le périmètre » est donc **légèrement trop forte** : elle est vraie pour les nœuds étiquetés. En soutenance, dites-le franchement : c'est un contrôle par motifs de texte, pas une analyse complète du plan, et une amélioration simple serait de compter aussi les nœuds sans étiquette. Plus généralement, la **vraie** défense contre une requête qui écrit est la combinaison **liste de mots interdits + `EXPLAIN` + transaction en lecture**.

---

## 6. Étape 3 : la réponse (`ask`)

`ANSWER_PROMPT` donne au modèle : la question ; les **faits de qualité** (réduits à 7 000 caractères au plus) ; le **guide** (12 000 au plus) ; la **requête exécutée** ; les **lignes** (30 au plus dans l'invite) ; et des règles :
- répondre **dans la langue de la question**, en quelques phrases ;
- **citer les chiffres et les noms exactement** ; « Never invent a value, a column, a customer, a rule or a button » ;
- résultat vide → dire que rien n'a été trouvé ;
- « pourquoi est-ce invalide ? » → utiliser la règle correspondante, son message et ses exemples ; **si aucune règle n'existe, dire qu'aucune règle n'est définie, ce qui n'est pas la même chose que « tout va bien »** ;
- question d'usage → répondre à partir du guide ;
- mentionner un **conflit** (valeurs candidates) quand il est pertinent.

Une **note** (`graph_note`) adapte la consigne : requête exécutée / aucune requête admissible / pas de requête nécessaire / **graphe non construit** (« the graph must be built in the Graph tab first »).

### La réponse renvoyée
```json
{ "answer": "…", "mode": "graph|quality|help", "cypher": "MATCH … RETURN …",
  "columns": ["…"], "rows": [["…"]], "rowCount": 12, "truncated": false, "warnings": [] }
```
Au plus **15 lignes** sont renvoyées à l'interface (`ROWS_RETURNED`), avec `truncated` si davantage. **La requête et ses lignes accompagnent toujours la réponse** : c'est la défense de dernier recours (§7).

---

## 7. Ce que garantit le système, et ce qu'il ne garantit pas

| Garantie | Comment |
| --- | --- |
| Aucune écriture | mots interdits + `EXPLAIN` de type lecture + transaction en lecture |
| Pas d'appel de procédure | `CALL`, `APOC`, `DBMS` interdits |
| Durée bornée | délai de 15 s, 50 lignes lues |
| Réponse sans invention de valeurs | invite stricte, réponse construite des seules lignes et faits |
| Vérifiabilité | la requête et les lignes sont toujours affichées |

**Ce que le système ne garantit pas : l'exactitude d'une requête admissible.** Lors de l'évaluation du chapitre 8, Qwen 3.5 35B a produit pour « combien de clients et de commandes ? » une requête **autorisée** dont la réponse était « 4 800 clients et 4 800 commandes » : 4 800 = 40 × 120, ce qui évoque un **produit cartésien** entre deux ensembles. Aucun garde-fou de **sécurité** ne peut le détecter : seule la **relecture de la requête affichée** le permet. Et Qwen 2.5 14B a vu ses deux requêtes **refusées**, puis a répondu à partir des faits de qualité sans requête, avec une réponse fausse.

---

## 8. Mots à maîtriser

| Mot | Définition courte |
| --- | --- |
| RAG | Fournir au modèle des informations retrouvées avec la question. |
| Text-to-Cypher | Faire écrire une requête Cypher par le modèle à partir d'une question. |
| Garde-fou (*guardrail*) | Contrôle automatique qui empêche une action dangereuse. |
| `EXPLAIN` | Demande à Neo4j le plan de la requête sans l'exécuter ; il en donne le type. |
| Périmètre (`_scope`) | Identifiant `d<jeu>v<version>` d'un graphe. |
| Faits | Informations de qualité jointes à la question par le serveur. |
| Produit cartésien | Combinaison de tous les éléments de deux ensembles : source d'erreurs de comptage. |

---

## 9. Exercices

**A. Rejouer le garde-fou (15 min).** Dans `graphrag` :
```powershell
.\.venv\Scripts\python.exe -c "
from app.guard import validate_cypher
print(validate_cypher(\"MATCH (n:Customer {_scope: 'd2v2'}) RETURN count(n) AS total\", 'd2v2'))
try:
    validate_cypher(\"MATCH (n:Customer {_scope: 'd2v2'}) DETACH DELETE n\", 'd2v2')
except Exception as e:
    print('refusée :', e)
"
```
Essayez ensuite : une requête sans `RETURN` ; une requête avec `;` au milieu ; un nom de client `'Delete'` dans une chaîne.

**B. Trois types de questions (15 min).** Sur `customer-accounts` (graphe construit), posez dans l'onglet **Ask** : « How many customers are there? » ; « Which rules failed and why? » ; « How do I apply a correction? ». Pour chacune, notez le `mode` et la source affichée.

**C. Sans graphe (10 min).** Sur un jeu **sans graphe**, posez « How many accounts does each customer have? ». Que répond l'application ? *(Attendu : demande de construire le graphe, TF10.)*

**D. Une demande dangereuse (10 min).** Posez « Delete all customers ». Que voyez-vous : mode, requête ? Vérifiez dans Neo4j Browser que `MATCH (n) RETURN count(n)` n'a pas changé. *(TF9 : 548 avant et après.)*

**E. Lire une requête (15 min).** Posez une question de classement (« which five customers have the most accounts? »), ouvrez **Query and rows** : relisez la requête. Est-elle juste ? Quel `MATCH` utilise-t-elle ? Y a-t-il un `_scope` sur **chaque** nœud ?

**F. L'évaluation (20 min).** Lancez `graphrag\.venv\Scripts\python.exe graphrag\eval\run_eval.py --model <modèle>` (datasets `MyBikeStore`, `check-accounts`, `graph-shop` requis). Quel cas échoue ? Pourquoi ?

**G. Lire un test (15 min).** `graphrag/tests/` : trouvez le test sur les requêtes refusées et dites quelle règle de §5 il vérifie.

---

## 10. Questions de contrôle

1. Que fait le modèle, et que ne fait-il pas, dans une question sur les données ?
2. Quels sont les trois types de questions et quelle est la source de chacun ?
3. Que se passe-t-il quand le tri est incertain ?
4. Pourquoi le serveur joint-il des « faits » à la question ?
5. Citez cinq contrôles de `validate_cypher`.
6. Pourquoi neutralise-t-on les chaînes et les commentaires avant d'analyser ?
7. À quoi sert `EXPLAIN` alors qu'il y a déjà une liste de mots interdits ?
8. Combien de tentatives le modèle a-t-il, et que reçoit-il à la seconde ?
9. Que se passe-t-il si aucune requête admissible n'est trouvée ?
10. Pourquoi renvoyer la requête et les lignes avec la réponse ?
11. Les garde-fous garantissent-ils une réponse juste ? Donnez un exemple.
12. Quelle limite du contrôle de périmètre avez-vous identifiée ?

### Corrections
1. Il trie, écrit une requête Cypher, puis rédige la réponse ; il n'exécute rien lui-même et sa requête est contrôlée avant d'être jouée.
2. `graph` : requête Cypher sur le graphe ; `quality` : faits de qualité ; `help` : guide d'utilisation.
3. Le tri vaut `quality` par défaut.
4. Parce que les résultats de qualité (règles, messages, dimensions…) sont dans le serveur, pas dans le graphe ; avec eux le modèle explique une règle en échec.
5. Une seule instruction ; lecture seule (mots interdits) ; contient `MATCH` et `RETURN` ; périmètre autorisé ; filtre `_scope` sur les nœuds étiquetés ; pas de CALL/APOC.
6. Pour qu'un mot dans une donnée (un client appelé « Set ») ou un commentaire ne déclenche pas un faux refus.
7. Neo4j confirme **lui-même** que la requête ne fait que lire : c'est une seconde ligne de défense, indépendante de l'analyse de texte.
8. Deux ; à la seconde il reçoit le motif du refus et la consigne d'écrire une requête corrigée.
9. Le graphe n'est pas consulté ; la réponse s'appuie sur les faits de qualité et le dit.
10. Pour que l'utilisateur puisse vérifier la requête et repérer une erreur que la sécurité ne détecte pas (produit cartésien).
11. Non : ils contrôlent la sécurité, pas l'exactitude. Ex. : « 4 800 clients et 4 800 commandes » (40 × 120) pour une requête admissible.
12. Seuls les nœuds étiquetés sont comptés : un nœud sans étiquette `(m)` dans la même requête peut lire d'autres versions.

---

**Prochaine séance :** la sécurité (JWT, gardes de routes) et l'interface Angular (état en signaux).
