# Séance 4 — L'analyse contrôlée par le modèle

**Objectif :** comprendre comment la plateforme utilise un modèle de langage **sans lui faire confiance**. C'est la partie la plus technique du worker, celle que le jury interrogera le plus : *« comment évitez-vous les hallucinations ? »*

**Durée :** 2 h · **Prérequis :** séances 2 et 3.

Fichiers (sous `Data_Quality_Wevioo/dq_pipeline/analyzers/`) :
- `langgraph_analyzer.py` (2 283 lignes) : le flux contrôlé et toutes les vérifications. On ne lit que les parties listées ci-dessous.
- `llm_analyzer.py` (259 lignes) : l'appel à Ollama et la lecture de la réponse.

---

## 1. Le principe en une phrase

> Le modèle **propose**, le code **vérifie** : une réponse n'est publiée comme venant du modèle que si elle passe des contrôles déterministes ; sinon on la répare une fois, puis on la remplace par une analyse calculée à partir des mesures.

Et surtout (§5) : **les constats chiffrés ne viennent jamais du modèle.**

---

## 2. Le flux : générer, valider, réparer, replier

Défini dans `_build_graph` (ligne 447) avec la bibliothèque LangGraph. Chaque rectangle est une fonction ; les flèches sont les transitions.

```
 START → build_context → generate → validate ─┬─ (aucune erreur) ───────────► finalize → END
                                              ├─ (erreurs, tentatives < 2) ─► repair ─┐
                                              │                                       └─► validate
                                              └─ (erreurs, tentatives ≥ 2
                                                  ou Ollama indisponible) ──► fallback → finalize
```

| Nœud | Fonction | Ce qu'il fait |
| --- | --- | --- |
| `build_context` | `_build_context_node` (500) | Construit les **preuves** (`evidence`) à partir du JSON SweetViz et l'invite (`prompt`). |
| `generate` | `_generate_node` (524) | Appelle le modèle avec un **message système** et un **schéma JSON** imposé. Compte une tentative. |
| `validate` | `_validate_node` (541) | Lit la réponse, la normalise, applique tous les contrôles. |
| `repair` | `_repair_node` (583) | Redemande au modèle en lui donnant ses **erreurs**, les preuves et sa réponse précédente. Température 0,1. |
| `fallback` | `_fallback_node` (601) | Fabrique une analyse à partir des seules mesures. |
| `finalize` | `_finalize_node` (621) | Ajoute un bloc `_validation` à l'analyse (workflow, tentatives, erreurs, avertissements, colonnes autorisées). |

### La décision après validation : `_route_after_validation` (ligne 638)
1. Ollama indisponible → `fallback` **tout de suite** (commentaire : *« retrying a repair prompt against a service that is down only adds latency »*).
2. Aucune erreur → `finalize`.
3. Des erreurs et `attempts < max_attempts` (2) → `repair`.
4. Sinon → `fallback`.

Comme `max_attempts = 2`, il y a **au plus deux appels** au modèle par table : un `generate` et un `repair`.

### Le statut du modèle (ligne 431)
| Situation | `llm_status` |
| --- | --- |
| Ollama injoignable, délai dépassé, erreur HTTP | `unavailable` |
| réponse valide | `ok` |
| réponse rejetée et repli utilisé | `invalid` |
| table vide ou fonction désactivée | `disabled` |
| résultat des mesures publié, analyse pas encore faite | `pending` |

Ce statut est publié avec l'analyse : l'interface peut dire « Déterministe seulement – Ollama indisponible ».

### Pourquoi une version « sans LangGraph » ?
`_run_controlled_workflow_without_langgraph` (ligne 474) fait exactement le même enchaînement avec une simple boucle `while`, au cas où la bibliothèque n'est pas installée dans l'image Docker (commentaire : *« before the Docker image is rebuilt »*). La clé `workflow` de `_validation` dit lequel a tourné.

---

## 3. L'appel au modèle : `llm_analyzer.py`

`analyze_table_metrics` (ligne 106) prépare le contexte puis appelle `analyze_table_with_langgraph` avec `llm_call=call_ollama` et `response_parser=parse_llm_response`. Elle ajoute à la description : le **contexte métier** (domaine, objectif, grain de ligne), le contexte libre de la table, et la ligne des **rôles détectés** (`describe_roles`, séance 3).

`call_ollama` (ligne 158) :
- envoie `POST {OLLAMA_URL}/api/generate`, non streamé ;
- `format` = le **schéma JSON** de la réponse (`ANALYSIS_RESPONSE_SCHEMA`) : Ollama contraint la sortie à cette forme ; si Ollama répond autre chose que 200, on **retente en simple mode JSON** ;
- `think = LLM_THINK` (faux par défaut : un modèle « raisonneur » peut dépenser tout son budget à réfléchir et couper le JSON) ;
- options : `temperature`, `top_p`, `num_predict` (4 096 par défaut), `num_ctx` (16 384) ;
- délai de connexion 15 s, de lecture `TABLE_LLM_TIMEOUT_SECONDS` (300 s) ;
- **toute** erreur réseau ou HTTP devient `LlmUnavailableError`. C'est ce qui sépare « le modèle est en panne » de « le modèle a mal répondu ».

La **température** est plafonnée à 0,25 et le `top_p` à 0,9 (`_bounded_float`, ligne 400 et 2269) : même si on configure plus, on ne laisse pas le modèle devenir très « créatif ».

`parse_llm_response` (ligne 240) essaie trois façons de lire du JSON : directement ; dans un bloc Markdown ```` ```json ````; en prenant du premier `{` au dernier `}`. Si rien ne marche, `None` → « LLM response is not valid JSON ».

---

## 4. Les preuves et l'invite

`_build_table_evidence` (ligne 649) construit un dictionnaire de **preuves** : le nom de la table, un aperçu (lignes, colonnes, doublons), et pour chaque colonne ses mesures. Il en tire la liste `allowed_columns` : **les seules colonnes que le modèle a le droit de citer**.

L'invite (`_build_analysis_prompt`, ligne 868) contient ces preuves, le contexte et la question libre. Le message système (`ANALYSIS_SYSTEM_PROMPT`) fixe les règles. La réponse doit respecter `ANALYSIS_RESPONSE_SCHEMA` : un objet avec `interpretation`, `quality_assessment`, `improvement_areas`, `resolution_steps`, `custom_analysis` et `semantic_analysis`.

---

## 5. Le point le plus important : `_apply_deterministic_quality`

Ligne 1559. Après avoir normalisé la réponse du modèle, le code **remplace** :
- `quality_assessment`,
- `improvement_areas`,
- `resolution_steps`

par des versions calculées **sans modèle** (`_deterministic_quality_from_evidence`) à partir des mesures. Il ajoute aussi les `supporting_evidence` et fusionne la partie `semantic_analysis` avec une version déterministe. Un avertissement est enregistré : *« quality metrics were computed deterministically from profiling evidence »*.

**Conséquence :** ce que dit le modèle sur **les chiffres** (pourcentages manquants, problèmes à traiter, étapes de résolution) n'est pas conservé : c'est le code qui l'écrit. Le modèle contribue au **texte** : le résumé (`interpretation`), la réponse à la question libre (`custom_analysis`), et les hypothèses de `semantic_analysis` (rôles, causes probables, impacts, règles de contrat, questions pour le propriétaire des données).

> **C'est exactement ce que le chapitre 8 a observé** : lors de ma première mesure sur l'analyse de table, les trois modèles citaient **les mêmes colonnes**, parce que ces points d'amélioration ne viennent pas d'eux. C'est un choix de conception, et c'est ce qui garantit zéro hallucination sur les chiffres.

---

## 6. Les contrôles de validité (ce qui fait échouer une réponse)

`_validate_and_normalize_analysis` (ligne 977) enchaîne :

| Contrôle | Fonction | Rejette si… |
| --- | --- | --- |
| Valeurs bidon | `_validate_placeholders` (1247) | une valeur est un gabarit (`field`, `column`, `critical`, `n/a`, `...`, `missingness_col1`…) ou une ellipse seule |
| Colonnes nommées | `_validate_explicit_field_references` (1269) → `_validate_field_value` (1446) | un champ `field`, `column`, `feature`… cite une colonne **inexistante** (« Unknown column reference ») |
| Affirmations dans le texte | `_validate_free_text_claims` (1323) → `_validate_missingness_claims` (1495) | une phrase attribue un **pourcentage de valeurs manquantes** faux à une colonne |
| Suppression dangereuse | `_is_dangerous_drop` (1552) | recommande de supprimer une colonne identifiant (`is_id_like`) manquante à moins de 50 % |

### Le contrôle des pourcentages, en détail
`_validate_missingness_claims` (ligne 1495) :
1. Ne s'intéresse qu'aux phrases qui parlent de valeurs manquantes (`MISSINGNESS_RE` : *missing, null, empty, blank, vide, manquant…*).
2. Pour chaque `NN %`, il **détermine à quelle colonne il se rapporte** (`_percentage_owner`) : la colonne nommée juste après (« 10 % of the email column ») ou, à défaut, la plus proche **avant**. Ainsi *« A has 10 % missing; B has 60 % missing »* attribue chaque pourcentage à la bonne colonne.
3. Il ignore « 100 % complete » ou « 100 % unique » (le contraire d'un manquant).
4. **Compare au réel :** erreur si `|affirmé − réel| > max(2 points, 15 % du réel)`.

Exemple : la colonne `email` a 4,0 % de manquants. « 4,5 % manquants » passe (écart 0,5 ≤ 2). « 12 % manquants » échoue (« Missingness claim for email says 12.00 % but evidence is 4.00 % »).

Il y a aussi des **avertissements** (qui n'invalident pas la réponse) : formulation « critique » pour une colonne à moins de 20 % de manquants ; impact « low » pour plus de 80 % de manquants ; impact « high » pour moins de 10 %.

---

## 7. Le repli : `_fallback_analysis_from_evidence`

Ligne 2091. Fabrique la même structure que celle du modèle, à partir des seules mesures : un résumé (« X contains N rows and M columns. The interpretation is generated from deterministic metrics because the LLM service (Ollama) was unavailable. »), des entités clés (colonnes identifiants), des relations (les associations fortes), la partie qualité déterministe, et une réponse à la question libre qui dit honnêtement *« This answer is based on the extracted metrics only »*, avec la raison du repli.

**Garantie :** quel que soit l'état du modèle, **un résultat exploitable est toujours publié**.

---

## 8. Mots à maîtriser

| Mot | Définition courte |
| --- | --- |
| Hallucination | Le modèle affirme une chose plausible mais fausse. |
| Sortie structurée | Réponse imposée sous forme d'un schéma JSON. |
| Réparation | Redemander au modèle en lui montrant ses erreurs. |
| Repli (*fallback*) | Résultat de remplacement calculé sans modèle. |
| Température | Réglage du caractère aléatoire de la génération. |
| LangGraph | Bibliothèque pour décrire un flux d'étapes avec des transitions conditionnelles. |
| Déterministe | Même entrée, même sortie, sans hasard. |

---

## 9. Exercices

**A. Voir le bloc `_validation` (10 min).** Ouvrez un fichier `Data_Quality_Wevioo/storage/json/<jeu>/v1/<table>_analysis.json`. Trouvez `llm_interpretation._validation` : valeur de `attempts`, `fallback_used`, `errors`, `warnings`, `allowed_columns`. Que dit l'avertissement « quality metrics were computed deterministically… » ?

**B. Provoquer un repli (15 min).**
1. Dans `Data_Quality_Wevioo/.env`, mettez `OLLAMA_URL` sur une adresse fausse (ou arrêtez Ollama).
2. Redémarrez Airflow (`docker compose --env-file Data_Quality_Wevioo/.env up -d`).
3. Déposez un CSV : quel `llm_status` ? Que dit l'interface ? Combien de tentatives (`attempts`) ?
*(Attendu : `unavailable`, texte « Déterministe seulement », `attempts: 1`, pas de réparation.)*
Remettez la bonne valeur ensuite.

**C. Jouer le validateur (20 min).** Dans `Data_Quality_Wevioo` :
```powershell
.\venv\Scripts\python.exe -c "
from dq_pipeline.analyzers.langgraph_analyzer import _validate_missingness_claims
col = {'name': 'email', 'missing_percent': 4.0}
for text in ['email has 4.5% missing values', 'email has 12% missing values']:
    errors, warnings = [], []
    _validate_missingness_claims(text, col, errors, warnings, ['email'])
    print(text, '->', errors)
"
```
Quel texte est rejeté ? Changez les pourcentages pour trouver la limite exacte (4 + max(2, 0,6) = 6 %).

**D. Lire les tests (20 min).** `tests/test_llm_answer_shapes.py` : trouvez trois tests et expliquez quelle forme de réponse « tolérée » ou « rejetée » ils couvrent.

**E. Relier au chapitre 8 (10 min).** Relisez dans le rapport la phrase « les trois modèles obtiennent exactement les mêmes colonnes citées ». Retrouvez dans ce fichier l'endroit du code qui l'explique.

---

## 10. Questions de contrôle

1. Quelles sont les quatre étapes du flux et dans quel ordre ?
2. Combien d'appels au modèle, au plus, par table ? Pourquoi ?
3. Que se passe-t-il si Ollama est arrêté ?
4. Quelle différence entre `unavailable` et `invalid` ?
5. Qu'est-ce que la « sortie structurée » et à quoi sert-elle ?
6. Pourquoi `think` est-il désactivé par défaut ?
7. Pourquoi plafonner la température ?
8. Que remplace `_apply_deterministic_quality` ? Que reste-t-il au modèle ?
9. Comment la plateforme vérifie-t-elle qu'un pourcentage est juste ?
10. Comment sait-elle à quelle colonne se rapporte un pourcentage dans une phrase ?
11. Qu'est-ce qu'un avertissement par opposition à une erreur ?
12. Pourquoi le résultat est-il toujours exploitable, même si le modèle échoue ?

### Corrections
1. Construire le contexte, générer, valider, puis (réparer puis valider de nouveau) ou finaliser ; sinon, repli.
2. Deux : un `generate` et un `repair` (`max_attempts = 2`) : on limite le coût et la latence.
3. Le flux passe directement au repli (inutile de réparer un service arrêté) ; statut `unavailable`.
4. `unavailable` : on n'a pas pu joindre le modèle (réseau, délai, erreur HTTP). `invalid` : le modèle a répondu, mais sa réponse a été rejetée par la validation.
5. On donne à Ollama un schéma JSON que la réponse doit respecter : elle est lisible par le code sans interprétation.
6. Un modèle raisonneur peut consommer tout le budget de sortie à « réfléchir » et couper le JSON, qui devient inutilisable.
7. Pour limiter l'aléatoire et obtenir des réponses plus stables et reproductibles.
8. Les `quality_assessment`, `improvement_areas` et `resolution_steps`, remplacés par des versions calculées. Le modèle garde le résumé, la réponse à la question libre et les hypothèses sémantiques.
9. En comparant, pour la colonne concernée, le pourcentage affirmé au pourcentage réel : erreur si l'écart dépasse le plus grand de 2 points ou 15 % du réel.
10. Par la colonne nommée juste après (« … of the email column »), sinon par la colonne la plus proche avant le pourcentage.
11. Une erreur invalide la réponse (réparation puis repli) ; un avertissement est seulement enregistré dans `_validation`.
12. Parce que le repli fabrique la même structure à partir des mesures ; il y a toujours un résultat, avec la raison du repli.

---

**Prochaine séance :** le score de qualité, les dimensions, et les règles.
