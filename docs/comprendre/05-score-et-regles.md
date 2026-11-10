# Séance 5 — Score, dimensions, règles de qualité

**Objectif :** savoir calculer à la main le score d'une table et d'une version, expliquer les quatre dimensions, et suivre le cycle de vie d'une règle : proposition, approbation, évaluation, alerte.

**Durée :** 2 h · **Prérequis :** séances 0 à 4.

Fichiers :
- `backend/src/main/java/wevioo/backend/service/` : `PipelineIngestionService.java` (score d'une table), `QualityScores.java`, `QualityDimensionsService.java`, `RuleService.java` (548 lignes), `VersionDiffService.java`.
- `backend/.../domain/` : `QualityRule`, `RuleType`, `RuleFormat`, `RuleState`, `RuleStatus`, `RuleSeverity`, `RuleOrigin`.
- `Data_Quality_Wevioo/dq_pipeline/analyzers/rule_suggester.py` : les règles proposées.
- `frontend/src/app/shared/format.ts` : la lettre (A à D).

---

## 1. Le score d'une table

`PipelineIngestionService.qualityScore` (ligne 287) :

```
score = max(0, min(100,  100  −  moyenne des % de valeurs manquantes par colonne  −  % de lignes dupliquées))
```

- La moyenne porte sur **toutes les colonnes** ; chaque colonne pèse autant.
- Pas de score (`null`) s'il n'y a aucune colonne ou **zéro ligne**.
- Le résultat est borné entre 0 et 100.

**Exemple :** une table de 1 000 lignes, 4 colonnes ; manquants 0 %, 0 %, 8 %, 12 % → moyenne 5 % ; 2 % de lignes dupliquées. Score = 100 − 5 − 2 = **93**.

### Le score d'une version : `QualityScores.rowWeighted`
Moyenne des scores de table **pondérée par le nombre de lignes** : une grosse table compte plus qu'une petite table de référence. Les tables `REMOVED` et celles sans score sont ignorées. Si toutes les tables notées sont vides, on retombe sur la moyenne simple. `null` si aucune table n'a de score.

**Exemple :** table A (900 lignes, score 95) et table B (100 lignes, score 60) → (95×900 + 60×100) / 1 000 = **91,5**, et non la moyenne simple 77,5.

### La lettre
Calculée **côté interface** (`qualityGrade`, `shared/format.ts` ligne 233), pas côté serveur : **A** à partir de 90 (« Healthy »), **B** à partir de 75, **C** à partir de 60, **D** en dessous. `–` si pas de score.

---

## 2. Les quatre dimensions : `QualityDimensionsService`

Elles complètent le score global en répondant à « *qu'est-ce qui est bon, qu'est-ce qui ne l'est pas ?* ». Chacune donne un **numérateur** et un **dénominateur**, ou « non mesurée » avec la raison. C'est un choix assumé (commentaire de la classe) : *« not measured … with the reason, instead of being given a flattering default »*.

| Dimension | Numérateur / dénominateur | « Non mesurée » quand… |
| --- | --- | --- |
| **Complétude** | cellules renseignées / (lignes × colonnes) | aucune table n'a de ligne |
| **Unicité** | lignes qui ne sont pas la copie exacte d'une autre / lignes | aucune ligne |
| **Validité** | valeurs au bon format / valeurs renseignées des colonnes dont le **rôle** est courriel, téléphone, URL ou date | aucune colonne n'a été reconnue comme telle |
| **Cohérence** | lignes où les règles d'ordre de dates **approuvées** sont respectées / lignes où les deux dates sont remplies | aucune règle d'ordre de dates approuvée, ou aucune ligne n'a les deux dates |

**Remarques :**
- L'unicité dit explicitement qu'*« un identifiant répété n'est pas un doublon »* : seules les lignes **entières** identiques comptent.
- La validité lit les statistiques de format mesurées par le worker, par le **rôle** de la colonne (séance 3) : sans bon rôle, pas de validité.
- La cohérence **dépend de règles que l'utilisateur a approuvées** : tant qu'aucune règle d'ordre de dates n'est approuvée, elle affiche « Not measured: no date-order rule is approved. Add one in the Rules tab. »

---

## 3. Une règle : le modèle de données

`QualityRule` (entité) :

| Champ | Sens |
| --- | --- |
| `tableName`, `columnName` | cible ; la colonne n'existe que pour les types qui en ont besoin |
| `type` | un des 9 `RuleType` |
| `threshold` | seuil (pour les types qui en ont besoin) |
| `severity` | `WARNING` ou `CRITICAL` |
| `enabled` | interrupteur (suspendre une règle) |
| `state` | `ACTIVE`, `SUGGESTED` ou `DISMISSED` |
| `origin` | `MANUAL` ou `AI` |
| `format`, `secondColumnName` | pour les règles de format et d'ordre de dates |
| `rationale` | la phrase qui explique pourquoi la règle est proposée |

**`isChecked()`** (`QualityRule.java` ligne 97) : `enabled && state == ACTIVE`. **Seules** les règles approuvées et activées sont évaluées. Une règle `SUGGESTED` ne l'est jamais tant que l'utilisateur ne l'a pas approuvée.

### Les 9 types (`RuleType`)
| Type | Condition de réussite |
| --- | --- |
| `NOT_NULL` | aucune valeur manquante dans la colonne |
| `MAX_MISSING_PERCENT` | au plus `seuil` % de manquants |
| `UNIQUE` | toutes les valeurs renseignées sont distinctes |
| `MIN_ROWS` | au moins `seuil` lignes |
| `MAX_DUPLICATE_PERCENT` | au plus `seuil` % de lignes dupliquées |
| `MIN_QUALITY_SCORE` | score de la table ≥ `seuil` |
| `MAX_ROW_DROP_PERCENT` | la table ne perd pas plus de `seuil` % de lignes par rapport à la version précédente |
| `MIN_FORMAT_VALID_PERCENT` | au moins `seuil` % des valeurs renseignées au `format` voulu |
| `DATE_ORDER` | sur les lignes où les deux dates sont remplies, la 1ʳᵉ n'est postérieure à la 2ᵉ que dans au plus `seuil` % des cas (0 par défaut) |

### Validation à la création : `RuleService.apply` (ligne 161)
Refuse (erreur 400) : un type inconnu ; une table vide ; un type qui demande une colonne sans colonne ; un format manquant ou inconnu ; une 2ᵉ colonne manquante ou **identique** à la première ; un seuil absent, négatif, infini ou non numérique.

---

## 4. D'où viennent les règles

### 4.1 À la main
`create` (ligne 139) : l'utilisateur choisit un type, une table, etc. ; la règle est créée **active**.

### 4.2 Proposées à partir de l'objectif (côté worker, phase 7)
`rule_suggester.py` :
1. **Preuves** (`build_evidence`) : pour chaque table, ses colonnes avec rôle, manquants, distinct, `format_valid_percent` (seulement si ≥ 20 % des valeurs ont le format) et les paires de dates mesurées.
2. **Invite** (`build_prompt`) : l'objectif, le domaine, le grain de ligne, les 6 types autorisés, et des **règles strictes** (« n'utiliser que les noms exacts des preuves », « une colonne qui se répète exprès ne doit pas être UNIQUE », « choisir des seuils qu'une personne accepterait, pas la valeur observée »). Sortie imposée par un schéma JSON (`RULE_SCHEMA`).
3. **Filtrage par le code** (`validate_suggestions` → `_validate_one`) : ne garde que les règles **vérifiables sur ces données**. Il écarte : un type hors des 6 suggérables ; une table ou une colonne **inconnue** ; une règle sans justification ; un format inconnu ou **non mesuré** sur cette colonne ; une paire de dates qui n'est pas une paire mesurée ; un seuil hors de 0 à 100. Au plus **15 règles**.
4. **Sans objectif ou sans modèle** : `deterministic_suggestions` dérive un petit ensemble de règles des mesures (formats que la colonne suit déjà, paires de dates reconnues par leurs noms).

> **Ce que j'ai vérifié en l'exécutant.** J'ai passé cinq règles à `validate_suggestions` : une règle de format sur `email` (gardée), une sur une colonne inventée (écartée : *unknown column*), une sur une table inventée (écartée : *unknown table*), un seuil de 500 (écarté : *outside 0-100*) et une règle `UNIQUE` sur `customer_id`, une **référence qui se répète exprès** (gardée !). La consigne « ne pas mettre `UNIQUE` sur une référence » n'existe donc **que dans l'invite** : le code ne la contrôle pas. Si le modèle la viole, la règle sera proposée et échouera ensuite à l'évaluation. C'est une limite à connaître et à pouvoir admettre en soutenance.

### 4.3 Côté serveur : `saveSuggestions` (ligne 85)
Le serveur **revalide** chaque proposition (`apply`, donc le même contrôle qu'à la création) et :
- écarte une règle invalide (journalisée) ;
- écarte une règle **déjà connue** (`sameCheck` : mêmes type, table, colonne, format, 2ᵉ colonne), **y compris une règle déjà refusée** : une suggestion écartée ne revient pas ;
- enregistre les autres avec `state = SUGGESTED`, `origin = AI` et la justification ;
- émet l'événement `RULES_SUGGESTED` (« N quality rule(s) suggested from your goal »).

### 4.4 Approuver ou écarter
`approve` (ligne 66) : `state = ACTIVE`, `enabled = true`. `dismiss` (ligne 75) : `state = DISMISSED` (la règle reste, masquée, pour qu'on ne la repropose pas).

---

## 5. L'évaluation

### 5.1 `evaluate(datasetId, versionId)` (ligne 223)
1. Retient les règles du jeu qui sont `isChecked()`.
2. **Supprime** les anciens résultats de cette version (`deleteByVersion`).
3. Charge les tables de la version (sans les `REMOVED`).
4. Pour chaque règle, `evaluateOne` renvoie un `Outcome` (statut, valeur observée, message) ; le résultat est enregistré avec la **description** de la règle (`describe`) et la date.

### 5.2 `evaluateOne` (ligne 301)
- **Table absente de la version** ou sans résultats → `SKIPPED` (« Table X has no results in this version »).
- Règle **par colonne** : si la **colonne n'existe pas** dans cette version → **`FAIL`** (« Column X does not exist in this version »). Sinon, selon le type :
  - `NOT_NULL` : réussit si `missing == 0` ;
  - `MAX_MISSING_PERCENT` : réussit si `% manquants ≤ seuil` ;
  - `UNIQUE` : `répétées = renseignées − distinctes` ; réussit si 0 ; `SKIPPED` si la colonne n'a aucune valeur ;
  - `MIN_FORMAT_VALID_PERCENT` (`formatOutcome`, ligne 414) : lit `format_stats` ; `SKIPPED` si rien n'a été mesuré ; sinon réussit si `% valides ≥ seuil`, et ajoute **un exemple de valeur invalide** au message ;
  - `DATE_ORDER` (`dateOrderOutcome`, ligne 430) : `FAIL` si la 2ᵉ colonne n'existe pas ; `SKIPPED` si le couple n'a pas été mesuré ; sinon réussit si `% de violations ≤ seuil`.
- Règle **par table** : `MIN_ROWS`, `MAX_DUPLICATE_PERCENT` (`SKIPPED` s'il n'y a aucune ligne), `MIN_QUALITY_SCORE` (`SKIPPED` s'il n'y a pas de score), `MAX_ROW_DROP_PERCENT` (`SKIPPED` s'il n'existe pas de version précédente de la table).

> **Attention, une nuance que j'ai corrigée dans le rapport :** une **table** absente donne `SKIPPED`, mais une **colonne** absente donne **`FAIL`**. L'idée : si la colonne d'une règle approuvée a disparu, c'est un vrai problème à signaler.

### 5.3 Quand l'évaluation a-t-elle lieu ?
- **À la fin de chaque exécution** : `evaluateForRun` (ligne 262), appelée par `/complete`. Elle ne s'exécute que si l'exécution est `COMPLETED` ou `PARTIAL`, et s'il existe au moins une règle active. Elle ne lève **jamais** d'exception (`catch RuntimeException`) : *« a broken rule must not break the run »*.
- **À la demande** : le bouton « Vérifier maintenant » appelle `evaluate`.

### 5.4 L'alerte
Si au moins une règle échoue, `evaluateForRun` renvoie l'événement `RULES_FAILED` avec le message « *N of M quality rule(s) failed (K critical)* ». L'interface l'affiche de trois façons : **notification** en direct, **badge** dans la liste des jeux, **bandeau** dans la vue d'ensemble. `WARNING` et `CRITICAL` déclenchent toutes les deux l'alerte ; les `CRITICAL` sont **comptées à part**.

---

## 6. Mots à maîtriser

| Mot | Définition courte |
| --- | --- |
| Pondération | Donner plus de poids à ce qui est plus gros (ici, les lignes). |
| Dimension | Un angle de la qualité : complétude, unicité, validité, cohérence. |
| Seuil (*threshold*) | Valeur limite d'une règle. |
| Sévérité | `WARNING` ou `CRITICAL`. |
| Suggestion | Règle proposée, non évaluée tant qu'elle n'est pas approuvée. |
| `SKIPPED` | Règle impossible à évaluer (ex. table absente), différent de `FAIL`. |

---

## 7. Exercices

**A. Recalculer un score à la main (15 min).** Ouvrez l'onglet **Tables**, prenez une table : notez le % de manquants de chaque colonne et le % de doublons. Calculez `100 − moyenne − doublons`. Comparez au score affiché.

**B. Pondération (10 min).** Sur un jeu de plusieurs tables (`MyBikeStore`), notez le score et le nombre de lignes de chaque table, calculez la moyenne pondérée, comparez à la note affichée en haut.

**C. Cycle d'une règle (20 min).**
1. Dans l'onglet **Rules**, créez : `accounts.email`, type « format valide », format `EMAIL`, seuil 95 %, sévérité `CRITICAL`.
2. Cliquez **Vérifier maintenant** : quel résultat, quel message ?
3. Mettez le seuil à 80 % : que devient le résultat ?
4. Changez la colonne en `ghost` : que devient-il ? *(Attendu : FAIL « Column ghost does not exist in this version », et **pas** SKIPPED.)*

**D. Voir l'alerte (10 min).** Avec une règle qui échoue, relancez l'analyse (Retry) : quel événement voyez-vous passer dans le flux SSE de la séance 0 (`RULES_FAILED`) ?

**E. Le validateur de suggestions (15 min).** Reproduisez mon essai :
```powershell
cd Data_Quality_Wevioo
.\venv\Scripts\python.exe -c "
from dq_pipeline.analyzers.rule_suggester import validate_suggestions
ev=[{'table':'accounts','rows':100,'duplicate_percent':0.0,'columns':[{'name':'email','type':'object','role':'EMAIL','missing_percent':4.0,'distinct_percent':100.0,'is_id_like':True,'format_valid_percent':{'EMAIL':96.0}}],'date_pairs':[]}]
raw={'rules':[{'table':'accounts','column':'ghost','type':'NOT_NULL','severity':'WARNING','rationale':'x'}]}
print(validate_suggestions(raw, ev))
"
```
Que renvoie-t-il ? Ajoutez une règle valide sur `email` et vérifiez qu'elle est gardée.

---

## 8. Questions de contrôle

1. Quelle est la formule du score d'une table ? Donnez un exemple chiffré.
2. Pourquoi la note de la version est-elle pondérée par les lignes ?
3. Où est calculée la lettre A à D, et pourquoi pas côté serveur ?
4. Pourquoi afficher « non mesurée » plutôt que 100 % ?
5. Pourquoi la cohérence peut-elle être « non mesurée » même avec deux colonnes de dates ?
6. Quelles règles sont réellement évaluées ?
7. Que fait le serveur d'une règle proposée qui répète une règle déjà refusée ?
8. Quelle différence de résultat entre une table absente et une colonne absente ?
9. À quels moments les règles sont-elles évaluées ?
10. Pourquoi `evaluateForRun` ne lève-t-elle jamais d'exception ?
11. Quelle limite du filtre des règles proposées avez-vous constatée ?
12. Qu'est-ce qui déclenche une alerte, et sous quelles formes ?

### Corrections
1. `100 − moyenne des % de manquants par colonne − % de doublons`, borné à 0-100. Ex. : manquants 0, 0, 8, 12 → moyenne 5 ; doublons 2 % → 93.
2. Pour qu'une grosse table pèse plus qu'une petite table de référence.
3. Dans l'interface (`qualityGrade`, `format.ts`) : c'est une présentation (seuils 90/75/60), le serveur ne manipule que le nombre.
4. Pour ne pas afficher une valeur flatteuse quand on n'a rien mesuré.
5. Parce qu'elle dépend de règles d'ordre de dates **approuvées** : sans règle, rien à vérifier.
6. Celles qui sont à la fois `ACTIVE` et activées (`isChecked`).
7. Il l'écarte : une règle déjà connue, même refusée, n'est pas proposée de nouveau.
8. Table absente → `SKIPPED` ; colonne absente → `FAIL`.
9. À la fin de chaque exécution (`COMPLETED` ou `PARTIAL`) et à la demande.
10. Pour qu'une règle défaillante ne fasse pas échouer toute l'exécution : les règles sont un plus.
11. Une règle `UNIQUE` sur une colonne qui se répète exprès n'est pas écartée par le code : seule l'invite l'interdit.
12. Au moins une règle échouée : événement `RULES_FAILED`, avec notification, badge et bandeau ; les critiques sont comptées à part.

---

**Prochaine séance :** la fiabilité (reprise après redémarrage, surveillance des exécutions bloquées, flux temps réel).
