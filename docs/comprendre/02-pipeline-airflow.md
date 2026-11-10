# Séance 2 — Le pipeline Airflow : les 9 phases du worker

**Objectif :** comprendre comment le worker transforme un dossier de CSV en résultats publiés, phase par phase, et pourquoi il est écrit comme une suite de petites étapes reliées par des fichiers.

**Durée :** 2 h · **Prérequis :** séances 0 et 1.

Tous les fichiers sont sous `Data_Quality_Wevioo/`.

---

## 1. L'organisation du code

| Fichier | Rôle |
| --- | --- |
| `dags/data_quality_pipeline.py` | Déclare le DAG : les 9 tâches et leur ordre (vu en séance 0). Ne contient presque pas de logique. |
| `dq_pipeline/runners.py` (458 lignes) | Une fonction `phase_*` par tâche. C'est le **chef de chaque phase**. |
| `dq_pipeline/pipeline_runner.py` (986 lignes) | La classe `PipelineRunner` : tous les outils (lire un CSV, calculer des métriques, signatures, relations, appeler le modèle). |
| `dq_pipeline/backend_client.py` | Les appels HTTP vers le serveur (les 5 rappels), avec réessais. |
| `dq_pipeline/storage.py` | Où écrire les résultats (`storage/json/...`, dossier d'exécution). |
| `dq_pipeline/models.py` | Les structures de données (`TableAnalysisResult`, `DatasetMetrics`, `ColumnMetrics`…). |
| `dq_pipeline/config/settings.py` | Les réglages lus dans l'environnement (modèle, délais, activations). |

**Idée-clé :** le DAG décide **quand**, `runners.py` décide **quoi faire dans chaque phase**, `pipeline_runner.py` fournit **comment** (les outils).

---

## 2. Pourquoi des phases séparées reliées par des manifestes

Chaque phase est un petit programme indépendant. Elles ne se passent pas des objets en mémoire : elles écrivent un **manifeste** JSON dans le dossier de l'exécution (`_write_manifest`, ligne 50 de `runners.py`) et la suivante le relit (`_read_manifest`, ligne 57).

| Manifeste | Écrit par | Contient |
| --- | --- | --- |
| `run_context.json` | phase 2 | identifiants, chemin, heure de départ |
| `tables_manifest.json` | phase 3 | la liste des tables trouvées |
| `profile_manifest.json` | phase 4 | tables profilées + tables en échec |
| `analysis_manifest.json` | phase 5 | tables analysées, publiées, en échec |
| `publish_manifest.json` | phase 6 | tables réellement publiées + échecs |
| `global_manifest.json` | phase 8 | l'analyse globale |

**Avantages :**
1. **Relance isolée.** Si la phase 5 échoue et qu'Airflow la rejoue (`retries=1`), elle relit les manifestes précédents et ne refait pas la lecture ni le profilage.
2. **Diagnostic.** On peut ouvrir un manifeste pour voir où l'exécution en était.
3. **Découplage.** Chaque phase ne dépend que d'un fichier, pas de l'état d'une autre.

**Revers :** il faut garder la cohérence entre ce que chaque phase écrit et lit.

---

## 3. Les 9 phases

Chaque `phase_*` suit le même schéma : crée un `PipelineRunner` via `_phase_runner` (ligne 29), fait son travail dans un `try`, et appelle `close_backend_client()` dans un `finally` pour fermer proprement la connexion HTTP.

### Phase 1 — `validate_backend_request` (dans le DAG)
Vérifie que `dag_run.conf` contient `dataset_id`, `version_id`, `pipeline_run_id`, `file_path` (`_require_conf_value`). Sans eux, l'exécution s'arrête tout de suite avec un message clair.

### Phase 2 — `phase_start_pipeline` (ligne 65)
1. Teste la santé du serveur ; si elle échoue, **avertit mais continue**.
2. Appelle `/start`. Si cet appel échoue, **lève une erreur** : sans cela le serveur ne saurait pas que l'exécution tourne.
3. Écrit `run_context.json`.

### Phase 3 — `phase_discover_tables` (ligne 90)
`_discover_table_files` (pipeline_runner ligne 85) liste les `.csv` triés du dossier, **sauf** `dataset_config.csv` (contexte, pas une table). Une table = un fichier ; son nom est le nom du fichier sans extension. Aucun CSV → erreur.

### Phase 4 — `phase_profile_tables` (ligne 104)
Pour chaque table :
1. `_load_dataset` lit le CSV (voir §4). `None` → table en échec, avec la raison.
2. **Table vide** (0 ligne) : SweetViz ne sait pas profiler un tableau vide. On écrit des métriques minimales (`_empty_table_metrics`) et on marque la table `empty`. Elle sera publiée à zéro ligne et signalée en échec.
3. Sinon `_run_sweetviz` profile la table et écrit un JSON de métriques. Le worker n'écrit **pas** de page HTML : l'interface construit ses écrans elle-même.
Si **aucune** table n'est profilée, la phase échoue.

### Phase 5 — `phase_analyze_tables` (ligne 149) — la plus importante
Pour chaque table profilée :
1. Charge de nouveau le CSV et le JSON SweetViz.
2. `_extract_metrics` calcule les métriques de la table et de chaque colonne (séance 3).
3. `_apply_runtime_quality_overrides` aligne le JSON destiné au modèle sur les métriques calculées depuis le tableau réellement chargé (nombre de lignes, doublons, paramètres de lecture CSV).
4. Construit un résultat (`ResultBuilder.table_analysis`) avec la **signature** (`_build_data_signature`), les contrôles de dates (`_cross_checks`), et les relations avec les autres tables.
5. **Premier envoi** au serveur (`post_table_analysis`) avec `llm_status = "pending"` : l'utilisateur voit la table.
6. `_refine_column_roles` : le modèle tranche les rôles de colonnes restés ambigus. **Ne fait jamais échouer l'exécution** (`try/except` qui avertit seulement).
7. `_run_llm_analysis` : l'analyse du modèle (séance 4). Puis **deuxième envoi** avec le statut du modèle et l'interprétation.

Chaque table est dans son propre `try/except` : si une seule échoue, les autres continuent, et l'exécution finira `PARTIAL`.

### Phase 6 — `phase_publish_table_results` (ligne 266)
Filet de sécurité : relit `analysis_manifest.json` et **renvoie** au serveur les tables dont le dernier envoi n'a pas été accepté. Si **aucune** table n'est publiée, la phase échoue.

### Phase 7 — `phase_suggest_rules` (ligne 302)
**Au mieux** (*best effort*) : elle n'élève jamais d'erreur, pour qu'une règle non proposée ne fasse pas échouer l'exécution. `rule_suggester.suggest_rules` propose des règles à partir de l'objectif (séance 5) ; on les envoie à `/rule-suggestions`.

### Phase 8 — `phase_global_analysis` (ligne 367)
Calcule les totaux (lignes, colonnes), construit une **interprétation déterministe** (`_build_portfolio_interpretation`) puis, si le modèle global est activé, une interprétation du modèle avec repli sur la déterministe. Envoie à `/global-analysis`.

### Phase 9 — `phase_complete_pipeline` (ligne 434)
S'exécute **toujours** (règle `ALL_DONE`). Lit `publish_manifest.json`, retrouve les tables en échec (`latest_failed_tables`, ligne 408 : prend le manifeste le plus avancé), puis `completion_status` (ligne 423) :

| Situation | Statut |
| --- | --- |
| aucune table publiée | `failed` |
| au moins une table en échec | `partial` |
| toutes publiées | `success` |
| un statut imposé par le DAG (une phase a échoué) | `failed` |

Elle envoie ce statut à `/complete` avec un message lisible (« 2 table(s) could not be analysed: … »).

---

## 4. La lecture tolérante : `read_csv_flex`

Fichier : `dq_pipeline/extractors/comprehensive_sweetviz_extractor.py`, ligne 296. Le problème : les CSV réels ont des encodages et des séparateurs variés, et une lecture naïve produit une table à une seule colonne.

1. **Encodage.** Lit les 1 Mo premiers octets et essaie dans l'ordre `utf-8-sig`, `utf-8`, `cp1252`, `windows-1252`, `iso-8859-1`, `latin1` : le premier qui décode sans erreur est retenu.
2. **Séparateur.** Sur les 50 premières lignes non vides, compte pour `;`, `,`, tabulation et `|` combien de lignes en contiennent et combien d'occurrences ; le meilleur score gagne.
3. **Tentatives.** Construit une liste de combinaisons (encodage, séparateur, moteur `c` ou `python`) : d'abord la combinaison détectée, puis les autres séparateurs, puis les autres encodages.
4. Essaie chaque combinaison **en mode strict**. Une lecture qui donne **plus d'une colonne** est acceptée tout de suite. Sinon on garde la meilleure lecture à une seule colonne.
5. Si tout échoue en strict, **refait les tentatives en sautant les lignes mal formées** (`on_bad_lines="skip"`) et ajoute l'avertissement « Strict CSV parsing failed; malformed rows were skipped… ». C'est pourquoi le nombre de lignes peut être inférieur à celui du fichier (le README du worker le dit).
6. Les paramètres retenus sont collés dans `df.attrs` (`csv_encoding`, `csv_separator`…), puis publiés dans les métriques.

> **À retenir pour la soutenance :** *« on choisit la lecture qui produit le plus de colonnes »* est un critère simple et efficace pour trouver le bon séparateur.

---

## 5. La signature de contenu

`_build_data_signature` (ligne 698) :
- `content_hash` : SHA-256 des valeurs (`pd.util.hash_pandas_object`, **ligne par ligne, ordre inclus**), indépendant de l'encodage du fichier ;
- `schema_hash` : SHA-256 de la liste `(nom de colonne, type)` ;
- et des compteurs (lignes, colonnes, doublons, par colonne : manquants et distincts) pour pouvoir comparer avec des signatures d'anciennes versions.

C'est ce que le serveur compare (séance 0) pour dire `NEW`, `MODIFIED`, `UNCHANGED`. Modifier une seule cellule change `content_hash`.

---

## 6. Les rappels vers le serveur : `backend_client.py`

- `_post_with_retry` (ligne 181) : réessaie sur erreur 5xx, délai dépassé, erreur de connexion, ou statuts listés (404, 409 pour `/start`), avec une **attente exponentielle** `time.sleep(2 ** attempt)` (2, 4, 8, … secondes).
- `CALLBACK_ATTEMPTS = 7` (ligne 17) : les rappels importants (table, global, fin) font **7 tentatives**, soit environ deux minutes d'attente cumulée : un redémarrage du serveur n'interrompt pas l'exécution.
- Chaque appel envoie l'en-tête `X-Pipeline-Token`.

---

## 7. Mots à maîtriser

| Mot | Définition courte |
| --- | --- |
| DAG | Graphe de tâches d'Airflow. |
| Manifeste | Petit fichier JSON qui transmet l'état d'une phase à la suivante. |
| `retries` / `retry_delay` | Réessais d'Airflow sur une tâche en échec (1 réessai après 5 min). |
| `ALL_DONE` | Règle : la tâche s'exécute quand toutes les précédentes sont terminées, réussies ou non. |
| Best effort | Fait de son mieux, sans jamais faire échouer l'ensemble. |
| Backoff exponentiel | Attendre de plus en plus longtemps entre deux tentatives. |

---

## 8. Exercices

**A. Suivre une exécution dans les manifestes (15 min).** Déposez un CSV. Puis ouvrez `Data_Quality_Wevioo/storage/pipeline_runs/<run_id>/` (l'identifiant est visible dans l'interface). Ouvrez chaque manifeste dans l'ordre. Dans `analysis_manifest.json`, retrouvez la clé `published`.

**B. Provoquer une table en échec (15 min).** Déposez deux fichiers : un CSV normal et un CSV ne contenant que l'en-tête (`id,nom`). Quel statut final ? Quel message ? Quelle table apparaît en échec ?
*(Attendu : `PARTIAL`, message « 1 table(s) could not be analysed: … Header-only CSV… », la table vide est publiée à 0 ligne.)*

**C. Voir la lecture tolérante (10 min).** Créez un CSV en ISO-8859-1 avec le séparateur `;` et des accents (c'est le test TF2). Déposez-le : combien de colonnes ? Dans les journaux de la tâche `profile_tables_with_sweetviz` d'Airflow, retrouvez la ligne « Loaded dataset: … using encoding=…, separator=… ».

**D. Casser une phase (15 min).** Dans Airflow, ouvrez le journal de la tâche `extract_metrics_and_run_llm` d'une exécution. Combien de fois voyez-vous « Published … » pour la même table ? Pourquoi ?

**E. Lancer les tests du worker (10 min).** `cd Data_Quality_Wevioo` puis `venv\Scripts\python.exe -m pytest -q`. Ouvrez `tests/test_pipeline.py` et lisez `test_header_only_table_is_published_and_reported_as_partial`.

---

## 9. Questions de contrôle

1. Pourquoi découper le pipeline en 9 phases plutôt qu'en une seule fonction ?
2. Quel est le rôle d'un manifeste ? Donnez-en deux.
3. Que fait la phase 2 si le contrôle de santé échoue ? Et si l'appel `/start` échoue ?
4. Que devient une table vide ?
5. Pourquoi la phase 5 envoie-t-elle chaque table deux fois ?
6. Pourquoi l'affinage des rôles par le modèle ne doit-il jamais faire échouer l'exécution ?
7. À quoi sert la phase 6 si la phase 5 envoie déjà les résultats ?
8. Pourquoi la phase 7 « n'élève jamais d'erreur » ?
9. Comment `completion_status` décide-t-il entre `success`, `partial` et `failed` ?
10. Comment `read_csv_flex` trouve-t-il le bon séparateur ?
11. Que contient la signature d'une table et à quoi sert-elle ?
12. Combien de tentatives et quelle attente pour un rappel au serveur ?

### Corrections
1. Pour relancer une phase seule, diagnostiquer précisément et rendre chaque étape visible dans Airflow.
2. Il transmet l'état d'une phase à la suivante par un fichier : relance isolée, diagnostic, découplage.
3. Avertit mais continue ; lève une erreur, car le serveur doit savoir que l'exécution a démarré.
4. Elle est conservée avec 0 ligne et les colonnes de son en-tête, marquée `empty`, publiée puis signalée en échec avec « Header-only CSV… ».
5. D'abord les mesures (rapides) pour que l'utilisateur voie la table, puis l'analyse du modèle (lente).
6. Parce que c'est une amélioration, pas une condition : sans elle, les rôles calculés par les indices restent valables.
7. C'est un filet : elle renvoie les tables dont l'envoi n'a pas été accepté (serveur momentanément indisponible).
8. Pour qu'une règle non proposée ne fasse pas échouer toute l'exécution : les règles sont un plus.
9. Aucune table publiée → `failed` ; au moins un échec → `partial` ; sinon `success` ; un statut imposé (phase en échec) prime.
10. Il compte, sur 50 lignes, combien en contiennent chaque candidat (`;`, `,`, tabulation, `|`) et prend le meilleur ; il vérifie ensuite que la lecture donne plus d'une colonne.
11. `content_hash` (valeurs), `schema_hash` (noms et types) et des compteurs ; le serveur les compare à la version précédente pour dire `NEW`, `MODIFIED`, `UNCHANGED`.
12. 7 tentatives, avec une attente de 2, 4, 8… secondes (backoff exponentiel), soit environ deux minutes.

---

**Prochaine séance :** les mesures, les rôles de colonnes et les relations entre tables (`column_roles.py`, `format_profile.py`).
