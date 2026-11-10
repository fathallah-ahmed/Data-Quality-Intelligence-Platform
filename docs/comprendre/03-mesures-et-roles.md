# Séance 3 — Mesures, rôles de colonnes, formats, relations

**Objectif :** comprendre comment le code décide, **sans modèle**, qu'une colonne est un identifiant, un courriel, un montant ou une date, et comment il repère les liens entre tables. C'est le cœur du principe « le code mesure, le modèle explique ».

**Durée :** 2 h · **Prérequis :** séances 0 à 2.

Fichiers (sous `Data_Quality_Wevioo/dq_pipeline/`) :
- `analyzers/column_roles.py` (346 lignes) : les rôles.
- `extractors/format_profile.py` (154 lignes) : formats et ordre des dates.
- `pipeline_runner.py` : `_extract_metrics` (ligne 174) et les relations entre tables (lignes 310-570).

---

## 1. Ce qui est calculé pour chaque table et chaque colonne

`_extract_metrics` (pipeline_runner ligne 174) construit :

**Pour la table** (`DatasetMetrics`) : nombre de lignes, de colonnes, de colonnes numériques, textuelles, de dates, **lignes dupliquées** (`df.duplicated().sum()`) et leur pourcentage.

**Pour chaque colonne** (`ColumnMetrics`) :
- `missing_count`, `missing_percent` ; `distinct_count`, `distinct_percent` ;
- `is_id_like` : vrai si toutes les valeurs sont distinctes **et** que la colonne n'est pas décimale (un montant unique n'est pas un identifiant) ;
- pour les colonnes numériques : minimum, quartiles Q1 / médiane / Q3, maximum, moyenne, écart type (les colonnes booléennes sont exclues : le calcul de quantile ne marche pas sur des booléens) ;
- `format_stats` = `profile_formats(col)` (§3) ;
- `traits` = `value_traits(col)` (§2.1) ;
- puis, **à la fin**, `assign_roles(column_metrics, row_count)` (ligne 265) donne à chaque colonne un rôle.

---

## 2. Le rôle d'une colonne : `column_roles.py`

### 2.1 Les trois familles d'indices
Le module le dit lui-même (en-tête) : *aucun indice ne décide seul ; ils s'additionnent*. Un nombre unique peut être un montant, un code postal peut n'être que des chiffres.

1. **Les mots du nom** (`LEXICON`, ligne 30). `_tokens` découpe le nom (`customerName` → `customer`, `name` ; `opened_on` → `opened`, `on`). Un mot du dictionnaire correspond si c'est exactement un jeton, ou, pour les mots d'au moins 4 lettres, le **début** d'un jeton (`telephone_number` matche `telephone`). Chaque correspondance ajoute **0,5** au rôle concerné.
2. **Les statistiques des valeurs** (`value_traits`, ligne 64) : longueur moyenne, longueur fixe, part de chiffres seuls, part de valeurs commençant par un zéro, part contenant un espace, part en « Majuscule puis minuscules », si les valeurs se lisent comme oui/non, si ce sont des entiers non négatifs. Calculées sur un échantillon de 5 000 valeurs au maximum (`random_state=0`, pour donner la même réponse à chaque fois).
3. **Les formats** (`format_stats`, §3) : part des valeurs qui sont un courriel, une URL, une date, un téléphone.

### 2.2 Le calcul : `score_column` (ligne 114)
Chaque règle ajoute des points à un rôle, **avec la raison** (`scores.add(role, poids, "pourquoi")`). Le score final d'un rôle est plafonné à 1,0. Les règles principales :

| Indice | Points | Rôle |
| --- | --- | --- |
| le nom contient un mot du lexique | +0,5 | ce rôle |
| ≥ 99 % de valeurs distinctes, pas décimale, plus d'une ligne | +0,3 | `IDENTIFIER` |
| + toutes les valeurs ont la même longueur (texte) | +0,1 | `IDENTIFIER` |
| deux valeurs seulement, lisibles oui/non | +0,6 | `BOOLEAN` |
| ≥ 20 lignes et (≤ 20 valeurs distinctes ou ≤ 5 %), pas décimale | +0,4 | `CATEGORY` |
| nombres décimaux | +0,3 | `AMOUNT` |
| valeurs de plus de 40 caractères | +0,4 | `FREE_TEXT` |
| ≥ 80 % des valeurs sont des courriels / URL / dates | +0,6 (+0,7 si ≥ 95 %) | `EMAIL` / `URL` / `DATE` |
| type `datetime` | +0,6 | `DATE` |

**Cas particulier important (lignes 134-141) :** un nom qui ressemble à un identifiant, mais dont **moins de 90 % des valeurs sont distinctes**, désigne probablement **une autre entité** (un `customer_id` sur une ligne de compte). Le rôle `IDENTIFIER` est alors remplacé par `REFERENCE` (+0,5 et +0,2 parce que les valeurs se répètent), et `IDENTIFIER` ne reste qu'en alternative.

**Résultat :** le rôle au score le plus haut, sa **confiance** (le score), les **indices** (au plus 4), et les 3 rôles suivants (`alternatives`). Si le meilleur score est inférieur à 0,3 (`MIN_ROLE_SCORE`) : `UNKNOWN`.

### 2.3 Cinq colonnes, calculées pour de vrai
J'ai exécuté `score_column` sur cinq colonnes types de 100 lignes (résultats réels) :

| Colonne | Rôle | Confiance | Les indices retenus |
| --- | --- | --- | --- |
| `email` (`c0@mail.com`…) | `EMAIL` | 1,0 | le nom contient `email` ; 100 % des valeurs ressemblent à des courriels |
| `account_id` (`A00000`…, toutes distinctes) | `IDENTIFIER` | 0,9 | le nom contient `id` ; 100 % distinctes ; même longueur |
| `customer_id` (30 valeurs distinctes sur 100) | `REFERENCE` | 0,7 | le nom ressemble à un identifiant ; ses valeurs se répètent |
| `opened_on` (dates `2024-01-01`…) | `DATE` | 0,7 | 100 % des valeurs ressemblent à des dates (le **nom ne dit rien**) |
| `balance` (décimaux) | `AMOUNT` | 1,0 | le nom contient `balance` ; décimaux ; nombres |
| `col7` (deux valeurs `x`/`y`) | `CATEGORY` | 0,4 | seulement 2 valeurs distinctes sur 100 lignes |

Notez `opened_on` : sans mot du lexique, les **formats** suffisent. C'est ce que dit le commentaire ligne 179 (*« such a column does not need the model »*).

### 2.4 Quand le modèle intervient
`is_unclear` (ligne 210) vaut vrai si :
- la confiance est inférieure à **0,65** (`UNCLEAR_BELOW`), **ou**
- le 2ᵉ rôle a au moins 0,4 et ne se trouve qu'à moins de **0,15** (`CLOSE_MARGIN`) du premier.

Dans la table ci-dessus, seule `col7` est « floue » (confiance 0,4 < 0,65) : elle est la **seule** soumise au modèle.

`refine_with_model` (ligne 298) construit une invite (`build_prompt`) avec : le nom de la table, le **domaine**, l'**objectif**, ce que **représente une ligne** (donnés à l'import), et, pour chaque colonne floue, son type, ses valeurs distinctes, des exemples, et ses candidats avec leurs scores. La sortie est **contrainte par un schéma JSON** (`LLM_SCHEMA`) : un tableau d'objets `{column, role, reason}` où `role` est limité à l'énumération `ROLES`.

Puis le code **vérifie** chaque réponse (lignes 322-339) :
- colonne inconnue → ignorée (avec une note) ;
- rôle hors liste → ignoré ;
- pas de raison → ignoré ;
- sinon : le rôle est retenu, `role_source` passe à `"llm"`, la confiance vaut **0,8** si le modèle a choisi un des candidats proposés, **0,6** sinon.

La fonction **ne lève jamais d'erreur** : en cas d'échec elle renvoie une liste de notes.

> **À retenir :** le modèle ne remplace pas les règles ; il ne peut que choisir parmi une liste fermée, pour des cas que les règles n'ont pas pu trancher, et sa réponse est contrôlée.

### 2.5 `describe_roles`
Une ligne de texte (`customer_id=REFERENCE, email=EMAIL, …`) ajoutée à l'invite d'analyse de la table (séance 4), pour que le texte écrit soit **cohérent avec les rôles détectés**.

---

## 3. Les formats et l'ordre des dates : `format_profile.py`

Le serveur ne relit jamais les CSV. Les contrôles qui ont besoin des valeurs sont donc **mesurés ici, une fois par exécution**, et le serveur les juge ensuite contre les règles approuvées (séance 5).

### 3.1 `profile_formats` (ligne ~85)
Pour une colonne **texte** uniquement (les colonnes numériques et date reçoivent `{}`) : compte, parmi les valeurs non vides, combien sont :
- `EMAIL` : `^[^@\s]+@[^@\s]+\.[^@\s.]{2,}$` ;
- `PHONE` : 7 à 15 chiffres, avec un `+` facultatif, après retrait des espaces, points, tirets et parenthèses ;
- `URL` : commence par `http://`, `https://` ou `www.` ;
- `DATE` : lue par `parse_dates` (une valeur n'est candidate que si elle a un chiffre **et** un séparateur ou un nom de mois, pour ne pas lire un simple nombre comme une date) ;
- `NUMBER` : convertible en nombre, la virgule décimale étant remplacée par un point.

Pour chaque format : `checked`, `valid`, et jusqu'à **3 exemples invalides**. C'est ce qui permet au serveur de dire « 93,0 % des valeurs sont des courriels, par exemple `c29.mail.com` ».

L'échantillon est de 20 000 valeurs au plus (`MAX_VALUES`, `random_state=0`).

### 3.2 `date_order_checks`
Pour chaque **paire** de colonnes de dates (au plus 6 colonnes de dates) : sur les lignes où **les deux** sont remplies, combien de fois la première est postérieure à la seconde, et l'inverse, avec des exemples. C'est la mesure derrière la règle « ouverture avant clôture ». Une colonne compte comme date si c'est un vrai `datetime`, ou si au moins **80 %** de ses valeurs se lisent comme des dates.

---

## 4. Les relations entre tables : le recouvrement de valeurs

Dans `pipeline_runner.py`. Idée : si presque toutes les valeurs d'une colonne d'une table se retrouvent dans une colonne d'une autre table, c'est probablement une clé étrangère.

**1. Profils (`_relationship_profiles_from_frame`, ligne 341).** Pour chaque colonne : jusqu'à 5 000 valeurs normalisées, `distinct_ratio`, `missing_percent`, et deux drapeaux :
- **cible possible** : (≥ 95 % de valeurs distinctes **et** ≤ 10 % de manquants) **ou** (nom d'identifiant, ≥ 50 % de distincts, ≤ 25 % de manquants) ;
- **source possible** : ≤ 95 % de manquants **et** ce n'est pas l'identifiant de sa propre table.

**2. Score (`_score_relationship_profiles`, ligne 385).** Pour chaque source, on teste chaque cible d'une **autre** table, de type compatible :
- `overlap` = valeurs en commun ; il en faut au moins 2 ;
- `coverage` = overlap / valeurs de la source ; **il faut au moins 0,85** ;
- si la source a moins de 5 valeurs et que les noms ne concordent pas, on écarte, sauf recouvrement très fort (≥ 0,98) entre un nom d'identifiant et l'identifiant de la cible ;
- confiance `high` si couverture ≥ 0,98 et (noms concordants ou la cible est l'identifiant de sa table), sinon `medium` ;
- score = `couverture × 0,72 + min(1, overlap/20) × 0,12 + score de nom × 0,12 + 0,04 si la cible est l'identifiant`.

**3. Choix.** On garde le meilleur candidat par source. **S'il n'est soutenu que par les valeurs** (pas de concordance de noms) **et** que le 2ᵉ est à moins de 0,04, on **écarte la relation** : mieux vaut ne rien dire que dire faux.

La relation est publiée avec `confidence`, `coverage`, `overlap_count` et une phrase d'explication. Elle est rattachée à la table **source** (`_relationships_for_table`, ligne 330).

---

## 5. Mots à maîtriser

| Mot | Définition courte |
| --- | --- |
| Rôle | Ce à quoi sert une colonne (identifiant, courriel, montant…). |
| Indice (*evidence*) | Raison qui a fait monter le score d'un rôle ; conservée pour l'expliquer. |
| Confiance | Le score du rôle retenu, plafonné à 1. |
| Alternative | Un autre rôle presque aussi probable. |
| Recouvrement (*overlap*) | Valeurs communes à deux colonnes. |
| Couverture (*coverage*) | Part des valeurs de la source qui existent dans la cible. |
| Clé étrangère | Colonne qui référence l'identifiant d'une autre table. |

---

## 6. Exercices

**A. Rejouer le calcul (15 min).** Dans `Data_Quality_Wevioo` :
```powershell
.\venv\Scripts\python.exe -c "
import pandas as pd
from dq_pipeline.analyzers.column_roles import score_column, value_traits, is_unclear
from dq_pipeline.extractors.format_profile import profile_formats
s = pd.Series(['c%d@mail.com' % i for i in range(100)])
d = s.nunique()
print(score_column('contact', str(s.dtype), d, d/100*100, profile_formats(s), value_traits(s), 100))
"
```
Le rôle est-il `EMAIL` alors que le nom `contact` n'est pas dans le lexique ? Pourquoi ? Changez le nom en `x`, puis changez les valeurs en `c%d.mail.com` (sans arobase) : que devient le rôle ?
*(Attendu : `EMAIL` grâce aux formats seuls ; sans arobase, la part de courriels tombe à 0 % et le rôle n'est plus `EMAIL` : il devient peu fiable, probablement `IDENTIFIER` avec une confiance faible, donc « flou ». Lancez-le pour vérifier : si le résultat diffère, c'est que j'ai mal lu une règle, dites-le-moi.)*

**B. Voir les indices dans l'interface (10 min).** Ouvrez l'onglet **Tables** d'un jeu de données, cliquez une colonne : l'application affiche son rôle et ses indices. Retrouvez la phrase correspondante dans `column_roles.py`.

**C. Provoquer une colonne floue (15 min).** Créez un CSV avec une colonne de 100 lignes aux valeurs `A`/`B` appelée `z`. Déposez-le. Dans les journaux de la tâche `extract_metrics_and_run_llm`, cherchez « Column roles of … ». Le modèle a-t-il été consulté ? Quel `role_source` voyez-vous dans la réponse de l'API (`GET .../tables/{id}`) ?

**D. Voir une relation (10 min).** Déposez `customers.csv` et `orders.csv` (dossier `Data_Quality_Wevioo/data/uploads/datasets/dataset_1/v1/`). Ouvrez l'onglet **Data model** : quelle relation est proposée, avec quelle couverture ?

**E. Lire les tests (15 min).** `tests/test_column_roles.py` et `tests/test_format_profile.py` : choisissez trois tests et dites ce qu'ils vérifient.

---

## 7. Questions de contrôle

1. Quels sont les trois types d'indices qui déterminent le rôle d'une colonne ?
2. Pourquoi les indices sont-ils *additionnés* plutôt qu'un seul décider ?
3. Que devient un nom qui ressemble à un identifiant si ses valeurs se répètent ?
4. À quoi sert de conserver les indices ?
5. À quelle condition une colonne est-elle envoyée au modèle ?
6. Que peut et que ne peut pas faire le modèle pour un rôle ?
7. Pourquoi les formats sont-ils mesurés dans le worker et non dans le serveur ?
8. Comment `profile_formats` évite-t-il de prendre un simple nombre pour une date ?
9. Comment est mesuré l'ordre de deux dates ?
10. Qu'est-ce que la « couverture » d'une relation et quel est le seuil ?
11. Pourquoi écarter une relation ambiguë plutôt que de choisir ?
12. Pourquoi l'échantillonnage utilise-t-il toujours `random_state=0` ?

### Corrections
1. Les mots du nom, les statistiques des valeurs, les formats des valeurs.
2. Parce qu'aucun n'est fiable seul : un nombre unique peut être un montant, des chiffres peuvent être un code postal.
3. Elle devient une `REFERENCE` : elle désigne probablement une autre entité (par exemple un identifiant de client sur une ligne de compte).
4. Pour pouvoir expliquer le choix à l'utilisateur et vérifier qu'il est raisonnable.
5. Si sa confiance est inférieure à 0,65, ou si le second rôle est à moins de 0,15 du premier (et vaut au moins 0,4).
6. Il choisit seulement un rôle dans la liste fermée `ROLES`, avec une raison, pour des colonnes que les règles n'ont pas tranchées ; le code ignore toute réponse hors liste, sans colonne connue ou sans raison.
7. Parce que le serveur ne relit jamais les CSV : il ne reçoit que des métriques et juge ensuite ces mesures contre les règles.
8. Une valeur n'est candidate à la lecture comme date que si elle contient un chiffre et un séparateur ou un nom de mois.
9. Sur les lignes où les deux dates sont remplies, on compte combien de fois la première est après la seconde et l'inverse.
10. La part des valeurs de la source qui existent dans la cible ; il faut au moins 0,85.
11. Une relation fausse induit l'utilisateur en erreur : si deux candidats sont très proches et qu'aucun nom de colonne ne départage, on ne dit rien.
12. Pour que deux exécutions sur les mêmes données donnent exactement les mêmes résultats.

---

**Prochaine séance :** l'analyse par le modèle, avec le flux contrôlé *générer, valider, réparer, replier*.
