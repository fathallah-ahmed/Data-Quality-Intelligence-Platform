# Séance 7 — Les corrections

**Objectif :** comprendre comment la plateforme corrige des données **sans jamais toucher à l'original** et **sans laisser un modèle de langage exécuter autre chose que des opérations connues**.

**Durée :** 2 h · **Prérequis :** séances 1 et 4.

Fichiers (sous `backend/src/main/java/wevioo/backend/`) :
- `service/CorrectionService.java` (192 lignes) : le chef d'orchestre.
- `correction/Operations.java` (192) : le catalogue des 12 opérations et leur validation.
- `correction/CorrectionEngine.java` (285) : l'exécution ligne à ligne.
- `correction/TableScanner.java` (237) : ce que l'application repère toute seule.
- `correction/CorrectionPlanner.java` : la traduction d'une phrase en opérations, par le modèle.
- `correction/DateNormalizer.java` : la lecture des dates.
- `correction/Csv.java` (249) : lecture et écriture des CSV.
- `controller/CorrectionController.java` : les routes.

---

## 1. Le principe de conception

> Le modèle n'écrit **aucun code** et **aucune valeur** : il ne peut qu'**assembler des opérations d'un catalogue fermé**. Chaque opération est **validée** contre les colonnes réelles, **prévisualisée** avec des chiffres, puis appliquée à une **copie**.

Trois garanties en découlent :
1. **Sûreté :** rien ne s'exécute qui ne soit dans la liste (`Operations`, commentaire d'en-tête : *« nothing can run that is not on the list »*).
2. **Transparence :** on voit ce qui va changer avant d'appliquer.
3. **Réversibilité :** l'original n'est jamais modifié ; la correction crée une **version dérivée** (séance 1) avec un journal.

---

## 2. Le parcours côté API

`CorrectionController`, sous `/api/datasets/{id}/versions/{versionId}/corrections` :

| Route | Méthode du service | Rôle |
| --- | --- | --- |
| `GET /suggestions?table=` | `suggestions` | ce que l'application repère dans la table |
| `POST /interpret` | `interpret` | traduit une phrase en opérations (modèle) |
| `POST /preview` | `preview` | aperçu chiffré, **rien n'est écrit** |
| `POST /apply` | `apply` | crée la **nouvelle version** corrigée |
| `GET /` | `log` | le journal d'une version corrigée |

L'interface enchaîne : suggestions (ou phrase) → liste d'opérations → aperçu qui se met à jour à chaque modification → application.

---

## 3. Le catalogue : `Operations`

Douze opérations. Onze portent sur **une colonne** ; `DROP_DUPLICATE_ROWS` sur toute la ligne.

| Opération | Réglage | Effet |
| --- | --- | --- |
| `TRIM` | — | retire les espaces autour des valeurs (y compris les espaces insécables et caractères invisibles) |
| `COLLAPSE_SPACES` | — | réduit les espaces répétés à un seul |
| `CASE` | `mode` : `UPPER`, `LOWER`, `TITLE` | change la casse |
| `REPLACE_VALUES` | `mappings` (paires `from → to`, 500 au plus), `ignoreCase` | remplace des valeurs **entières** |
| `REPLACE_TEXT` | `find` (1 à 100 caractères), `with` | remplace une **partie** d'une valeur |
| `EMPTY_TO_NULL` | `tokens` (50 au plus ; par défaut `N/A`, `NA`, `#N/A`, `null`, `none`, `nan`, `-`, `--`, `?`, `missing`) | transforme des marqueurs en cellules vides |
| `FILL_EMPTY` | `value` | remplit les cellules vides |
| `NORMALIZE_DATE` | `order` : `AUTO`, `DMY`, `MDY`, `YMD` | écrit les dates en `aaaa-mm-jj` |
| `DIGITS_ONLY` | — | ne garde que les chiffres (et un `+` initial) |
| `PAD_LEFT` | `width` (1 à 50), `char` | complète à gauche (zéros perdus) |
| `DROP_EMPTY_ROWS` | — | supprime les lignes où la colonne est vide |
| `DROP_DUPLICATE_ROWS` | — | supprime les copies exactes d'une ligne précédente |

### La validation : `Operations.validate`
Appelée sur **chaque** opération, qu'elle vienne de l'application ou du modèle. Elle :
- refuse un type inconnu (« Unknown correction ») ;
- **résout la colonne** : nom exact, sinon sans tenir compte de la casse, sinon **refus** (« The table has no column named X ») ; une colonne absente est donc impossible ;
- vérifie chaque réglage (mode de casse valide, au moins une paire pour `REPLACE_VALUES`, `find` non vide, largeur 1-50…) ;
- renvoie l'opération **sous sa forme normale** (vrai nom de colonne, réglages complétés).

Puis `Operations.describe` produit une phrase lisible pour l'aperçu et le journal (« Remove spaces around the values of email »).

---

## 4. Les corrections trouvées : `TableScanner`

`scan` lit le fichier et observe chaque colonne (valeurs fréquentes, espaces, marqueurs, dates, variantes), puis `suggestFor` propose :

| Constat | Proposition |
| --- | --- |
| des marqueurs d'absence (`N/A`, `-`…) | `EMPTY_TO_NULL` avec ces marqueurs |
| des espaces avant ou après des valeurs | `TRIM` |
| des espaces répétés | `COLLAPSE_SPACES` |
| une colonne dont ≥ **80 %** des valeurs sont des dates, certaines convertibles | `NORMALIZE_DATE` en `AUTO` (en signalant les valeurs ambiguës) |
| pour une colonne qui n'est pas une date : une valeur écrite de plusieurs façons qui ne diffèrent **que par la casse** (« france », « France ») | `REPLACE_VALUES` vers l'orthographe la plus fréquente (30 paires au plus) |

Au plus 25 suggestions, chacune avec **une justification**, le nombre de lignes concernées et des exemples.

---

## 5. La phrase en langage naturel : `CorrectionPlanner`

`plan(table, scan, instruction)` :
1. Refuse une demande vide.
2. Construit l'**invite** (`prompt`, ligne 103) avec : la table, la demande, la liste des **opérations autorisées** avec leurs réglages, et pour chaque colonne : nom, nombre de valeurs remplies et distinctes, **valeurs les plus fréquentes**. Les **règles strictes** de l'invite : n'utiliser que les colonnes données ; **ne jamais inventer de valeur** (un remplacement doit venir de la demande ou des valeurs fréquentes) ; ne pas deviner le format d'une date ambiguë (utiliser `AUTO` et le dire dans `assumptions`) ; si la demande n'est pas faisable avec ces opérations, **renvoyer une liste vide et dire pourquoi**.
3. Appelle Ollama (`generateJson`) avec un **schéma JSON** de sortie.
4. **Lit** la réponse, même si le modèle l'a entourée d'un bloc de code (`jsonObjectIn` prend du premier `{` au dernier `}`).
5. **Valide chaque opération** avec `Operations.validate`. Celles qui échouent vont dans `rejected` avec la raison, et **ne sont pas exécutées**. Au plus **20 opérations** (`MAX_OPERATIONS`).
6. Renvoie `Plan(operations, assumptions, rejected)` : l'utilisateur voit ce que le modèle a compris, ses hypothèses, et ce qui a été refusé.

> **Ce qu'il faut retenir :** même si le modèle se trompe ou « hallucine » une colonne, la validation la refuse. Le pire qui puisse arriver est une liste vide ou incomplète.

---

## 6. L'exécution : `CorrectionEngine.run`

Une seule passe, ligne par ligne (le fichier n'est pas chargé en mémoire), **la même** pour l'aperçu et l'application ; la différence est `output` : `null` pour l'aperçu (on mesure seulement), un fichier pour l'application.

1. `Csv.detect` devine l'**encodage** (UTF-8 sinon Windows-1252) et le **séparateur**.
2. L'**en-tête est recopié exactement** (`rawHeader`) : **renommer une colonne n'est pas une correction**, et la comparaison de versions l'afficherait comme une colonne supprimée et une ajoutée.
3. Pour chaque ligne : si le nombre de cellules diffère de l'en-tête, la ligne est **complétée** (cellules vides) ou **réduite** (si les cellules en trop sont vides). Si une cellule en trop contient des données, **erreur** : « the extra data would be lost. Fix the structure of the file first. » On ne perd jamais de données en silence.
4. Applique les opérations dans l'ordre. Pour une opération qui change une cellule, `result.change(...)` compte et garde jusqu'à **5 exemples** (ligne, avant, après).
5. Les opérations `DROP_*` retirent la ligne (`DROP_DUPLICATE_ROWS` mémorise l'empreinte de chaque ligne vue).
6. Renvoie : lignes avant/après, **cellules modifiées**, lignes ajustées, résultat de chaque opération.

### Le fichier écrit : `Csv.RecordWriter`
**UTF-8, séparateur virgule, fin de ligne `\n`**, guillemets seulement si la valeur contient une virgule, un guillemet ou un saut de ligne. Un fichier ISO-8859-1 avec `;` devient donc un fichier UTF-8 avec `,` : c'est cohérent avec ce que la plateforme lit ensuite, et le contenu est identique.

---

## 7. Les dates : `DateNormalizer`

Une date comme `01/02/2024` est **ambiguë** : 1ᵉʳ février ou 2 janvier ? La règle, en mode `AUTO` :

| Cas | Décision |
| --- | --- |
| `aaaa-mm-jj`, `aaaa/mm/jj`, `aaaa.mm.jj` | lue directement |
| `jj/mm/aaaa` avec le 1ᵉʳ nombre **> 12** (`25/12/2024`) | jour d'abord : 25 décembre |
| 2ᵉ nombre **> 12** (`12/25/2024`) | mois d'abord |
| deux nombres **égaux** (`03/03/2024`) | indifférent : converti |
| sinon (`01/02/2024`) | **ambigu : laissé tel quel**, compté « à revoir » |
| `12 Feb 2024`, `Feb 12, 2024` | lue avec le nom du mois |
| fuseau horaire, fractions de seconde, date impossible (`31/02/2024`) | **non reconnue** : laissée telle quelle |

Si l'utilisateur précise l'ordre (`DMY`, `MDY`, `YMD`), les ambiguïtés disparaissent. L'heure (heures, minutes, secondes) est conservée ; un fuseau serait perdu, donc la valeur n'est pas reconnue.

**Essai réel (TF7) :** sur `01/02/2024, 03/04/2024, 25/12/2024, 13/01/2024, 2024-05-06, 07/08/2024`, l'aperçu donne **2 modifiées** (`25/12/2024 → 2024-12-25`, `13/01/2024 → 2024-01-13`), **3 à revoir**, et l'avertissement « 3 date(s) in created can be read as day/month or month/day. They are left as they are; say how the dates are written to convert them. »

---

## 8. L'aperçu et l'application

### `preview` (CorrectionService)
Valide les opérations, lance `engine.run(..., output = null)`, puis `toPreview` construit les **avertissements** :
- « *… changes nothing in this table* » si une opération n'a rien modifié ;
- les dates ambiguës ou non reconnues (voir §7) ;
- « *N row(s) will be removed from the corrected version (the original keeps them)* » pour un `DROP_*` ;
- « *N row(s) have more or fewer cells than the header; they were padded or cut* ».

### `apply`
1. Valide les opérations ; **refuse** une liste vide (« Add at least one correction »).
2. Appelle `datasetService.createDerivedVersion(...)` (séance 1) avec **une fonction qui remplit le dossier** de la nouvelle version : **copie tous les fichiers** de la version source sauf la table corrigée, applique le moteur sur celle-ci et écrit le résultat avec le même nom, et écrit `corrections_log.json` (version d'origine, table, date, lignes avant/après, cellules modifiées, lignes ajustées, opérations).
3. La version dérivée passe par le **pipeline complet** comme un dépôt : elle sera donc analysée, notée, comparée avec la précédente.

---

## 9. Mots à maîtriser

| Mot | Définition courte |
| --- | --- |
| Catalogue fermé | Liste finie d'actions autorisées ; rien d'autre ne s'exécute. |
| Version dérivée | Nouvelle version créée à partir d'une autre, qui garde sa parenté. |
| Aperçu (*dry run*) | Calculer ce qui changerait sans rien écrire. |
| Ambigu | Qui peut se lire de deux façons (jour/mois). |
| Journal | Trace de ce qui a été corrigé, écrite avec la version. |
| Dialecte CSV | Encodage + séparateur d'un fichier. |

---

## 10. Exercices

**A. Suggestions (10 min).** Créez un CSV `essai.csv` avec une colonne `pays` : `France`, `france`, `FRANCE`, ` Tunisie `, `N/A`, 20 lignes mélangées. Déposez-le, ouvrez **Corrections**, choisissez la table. Quelles suggestions apparaissent ? Retrouvez dans `TableScanner.suggestFor` la ligne de chaque suggestion.

**B. Aperçu sans effet (10 min).** Retenez une suggestion, regardez l'aperçu (valeurs touchées, exemples). Vérifiez que **le fichier d'origine** dans `data/uploads/datasets/dataset_<id>/v1/` n'a pas changé (même date de modification).

**C. Appliquer (15 min).** Appliquez. Que voyez-vous : combien de versions ? Quel fichier supplémentaire dans le dossier de la version 2 ? Ouvrez `corrections_log.json`.

**D. Une phrase bien (et mal) comprise (15 min).** Dans le champ de texte, écrivez : *« mets tous les pays en majuscules »* puis *« supprime les clients morts »*. Que fait l'application ? *(Probable : la 1ʳᵉ devient une opération `CASE` en `UPPER` ; la 2ᵉ revient sans opération, avec une explication dans les hypothèses, parce que rien dans le catalogue ne correspond. Le résultat exact dépend du modèle ; l'important est que tout ce qui revient soit validé, donc inoffensif.)*

**E. Dates (10 min).** Rejouez TF7 : un fichier de dates `01/02/2024, 25/12/2024`. Comparez ce que fait `AUTO` et ce que fait `DMY`.

**F. Lire un test (15 min).** Ouvrez `backend/src/test/java/wevioo/backend/` et cherchez `CorrectionSafetyTest` : lisez le test sur les cellules vides au-delà de l'en-tête (`emptyCellsBeyondTheHeaderAreDroppedAndShortRowsArePadded`).

---

## 11. Questions de contrôle

1. Pourquoi corriger une copie plutôt que l'original ?
2. Combien d'opérations existent et pourquoi un catalogue fermé ?
3. Que peut faire le modèle dans une correction, et que ne peut-il pas faire ?
4. Que se passe-t-il si le modèle propose une colonne qui n'existe pas ?
5. Quelle différence entre `REPLACE_VALUES` et `REPLACE_TEXT` ?
6. Pourquoi l'aperçu et l'application utilisent-ils le même moteur ?
7. Pourquoi l'en-tête est-il recopié tel quel ?
8. Que fait le moteur d'une ligne qui a plus de cellules que l'en-tête ?
9. Comment `AUTO` traite-t-il `01/02/2024` ? Et `25/12/2024` ?
10. Que contient une version corrigée en plus d'une version déposée ?
11. Comment la version corrigée est-elle analysée ?
12. Dans quel format le fichier corrigé est-il écrit ?

### Corrections
1. Pour ne jamais détruire les données d'origine, pouvoir comparer et revenir en arrière.
2. Douze. Un catalogue fermé garantit que rien ne s'exécute hors liste ; chaque opération peut être validée, prévisualisée et expliquée.
3. Il peut seulement choisir des opérations du catalogue avec leurs réglages ; il ne peut ni écrire de code, ni inventer de valeur, ni agir sur une colonne inconnue.
4. La validation la refuse : l'opération va dans `rejected` avec la raison et n'est pas exécutée.
5. `REPLACE_VALUES` remplace des valeurs **entières** (typo, variante) ; `REPLACE_TEXT` remplace une **partie** d'une valeur (unité, préfixe).
6. Pour que l'aperçu affiche exactement ce que l'application fera : la seule différence est l'écriture du fichier.
7. Parce que renommer une colonne n'est pas une correction et fausserait la comparaison de versions.
8. Si les cellules en trop sont vides, elle les retire (ajustement compté) ; si l'une contient des données, il lève une erreur pour ne pas les perdre.
9. `01/02/2024` est ambigu : laissé tel quel et compté « à revoir » ; `25/12/2024` ne peut être que le 25 décembre : converti en `2024-12-25`.
10. Un fichier `corrections_log.json` et les champs `derivedFromVersionId` / `derivedFromVersionNumber`.
11. Comme un dépôt : le pipeline complet (profilage, rôles, analyse, score), puis comparaison avec la version précédente.
12. UTF-8, séparateur virgule, en-tête inchangé, guillemets seulement si nécessaire.

---

**Prochaine séance :** le graphe Neo4j (déduction du modèle, clés composées, conflits).
