# Service de questions (`graphrag`)

Service Python (FastAPI) qui répond, en langage naturel, aux questions posées dans l'onglet **Ask** de l'application. Il ne parle qu'au backend : le navigateur ne l'appelle jamais.

## Ce qu'il fait

Une question est d'abord **triée** par le modèle local (Ollama) en trois types :

| Type | Exemples | Source de la réponse | Neo4j ? |
| --- | --- | --- | --- |
| `graph` | « Combien de commandes par client ? », « Qui a le plus d'amis ? » | une requête Cypher écrite par le modèle, vérifiée puis exécutée sur le graphe de la version | oui |
| `quality` | « Quelles règles échouent et pourquoi ? », « Quelle colonne a le plus de valeurs manquantes ? » | les faits qualité joints par le backend (règles et leur message, dimensions, constats, rôles des colonnes) | non |
| `help` | « Comment appliquer une correction ? », « Que veut dire Critical ? » | le guide d'utilisation (`backend/src/main/resources/help/app-guide.md`) | non |

Seul le type `graph` a besoin de Neo4j : les deux autres répondent avant même que le graphe soit construit. Si une question sur le contenu des données arrive sans graphe, la réponse dit de le construire dans l'onglet Graph. Un tri incertain retombe sur `quality`.

Dans tous les cas, la réponse est rédigée **uniquement** à partir de ce qui est fourni (lignes obtenues, faits qualité, guide), avec la consigne de ne rien inventer. La requête exécutée et ses lignes sont renvoyées avec la réponse pour qu'on puisse la vérifier.

## Garde-fous sur le Cypher (`app/guard.py`)

Le modèle écrit la requête, donc rien de ce qu'il écrit n'est de confiance. Une requête ne s'exécute que si :

- c'est **une seule instruction**, en **lecture seule** (`CREATE`, `MERGE`, `DELETE`, `SET`, `CALL`, `LOAD`, `USING`, `APOC`, `DBMS`… sont refusés, hors des chaînes et commentaires) ;
- elle contient `MATCH` et `RETURN` ;
- **chaque nœud** est filtré sur `_scope: 'd<dataset>v<version>'`, celui de la version interrogée, et aucun autre scope n'apparaît ;
- Neo4j confirme, avec `EXPLAIN`, qu'elle ne fait que lire, puis elle s'exécute avec un délai maximal (15 s par défaut) ;
- au plus 50 lignes sont lues et 15 sont renvoyées à l'interface (`truncated` le signale) ; le modèle a **deux essais** et reçoit la raison du refus pour corriger.

Si aucune requête admissible n'est trouvée, la réponse s'appuie sur les faits qualité seuls et le dit.

## API

Toutes les routes sauf `/health` exigent l'en-tête `X-QA-Token` (valeur de `QA_API_TOKEN`).

| Méthode | Route | Description |
| --- | --- | --- |
| `GET` | `/health` | `{"status": "ok", "model": "<modèle>"}` |
| `POST` | `/ask` | `{"question": "...", "scope": "d150v155", "facts": {...}}` → réponse |

Réponse de `/ask` :

```json
{
  "answer": "…",
  "mode": "graph | quality | help",
  "cypher": "MATCH … RETURN …",
  "columns": ["…"],
  "rows": [["…"]],
  "rowCount": 12,
  "truncated": false,
  "warnings": ["Attempt 1: DELETE is not allowed: the query may only read"]
}
```

`facts` est construit par le backend (`QaService`) : `dataset`, `version`, `context`, `rules`, `dimensions`, `findings`, `columns`, `guide` et, si le graphe est construit, `graph` (schéma et comptages). Codes d'erreur : 401 jeton refusé, 422 entrée invalide (question vide ou de plus de 1000 caractères, scope mal formé), 400 question refusée, 503 Neo4j ou modèle injoignable (le message est destiné à l'utilisateur).

## Configuration

Variables d'environnement (mêmes noms que le backend et le worker) :

| Variable | Défaut | Rôle |
| --- | --- | --- |
| `OLLAMA_MODEL` | *(obligatoire)* | modèle Ollama utilisé pour trier, écrire le Cypher et rédiger la réponse |
| `OLLAMA_URL` | `http://localhost:11434` | adresse d'Ollama |
| `NEO4J_URI` | `bolt://localhost:7687` | adresse Bolt de Neo4j |
| `NEO4J_USER` / `NEO4J_PASSWORD` | `neo4j` / `dq-neo4j-dev` | identifiants (valeur de développement, à changer avant tout partage) |
| `QA_API_TOKEN` | `dev-qa-token` | jeton partagé avec le backend (valeur de développement) |
| `QA_QUERY_TIMEOUT_SECONDS` | `15` | durée maximale d'une requête Cypher |

Sans `OLLAMA_MODEL`, le service répond 503 avec un message explicite.

## Lancer

Avec `start-dev.ps1` (fenêtre « Questions »), avec Docker (`docker compose --env-file Data_Quality_Wevioo/.env up --build`, service `graphrag`, port 8090), ou à la main :

```powershell
cd .\graphrag
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
$env:OLLAMA_MODEL = 'gemma4:cloud'     # n'importe quel modèle présent dans Ollama
.\.venv\Scripts\python.exe -m uvicorn app.main:app --port 8090
```

## Tests et évaluation

```powershell
.\.venv\Scripts\python.exe -m pip install pytest httpx
.\.venv\Scripts\python.exe -m pytest                      # 39 tests : garde-fous, tri, réponses, service HTTP
.\.venv\Scripts\python.exe eval\run_eval.py --model gemma4:cloud
```

Les tests utilisent un modèle scripté et un faux Neo4j : ils ne demandent ni Ollama ni Neo4j. `eval/run_eval.py` fait autre chose : il rejoue les questions de `eval/cases.json` (données, qualité, usage, demandes d'écriture refusées) contre un backend, un service et un Ollama **en marche**, et vérifie le type de réponse et les mots attendus. Les cas fournis sont écrits pour les datasets de test du projet : à adapter aux vôtres. Code de sortie 1 si un cas échoue ; pratique pour comparer deux modèles.

## Organisation

| Fichier | Rôle |
| --- | --- |
| `app/main.py` | application FastAPI, authentification par jeton, erreurs |
| `app/qa.py` | tri, génération et exécution du Cypher (composants `neo4j-graphrag`), rédaction de la réponse |
| `app/guard.py` | validation des requêtes |
| `app/schema.py` | ce que le modèle sait du graphe (schéma, exemples), des faits qualité et du guide |
| `app/config.py` | lecture de l'environnement |
| `eval/` | jeu de questions rejouable |
| `tests/` | tests unitaires et du service |
