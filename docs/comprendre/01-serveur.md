# Séance 1 — Le serveur : dépôt, versions, relance

**Objectif :** comprendre `DatasetService`, le fichier le plus important du serveur (1 023 lignes), sans le lire en entier. Vous devez pouvoir expliquer comment un dépôt devient une version, pourquoi on détecte les doublons, et ce que font la relance, la suppression et les versions dérivées.

**Durée :** 2 h · **Prérequis :** séance 0.

Tous les fichiers cités sont sous `backend/src/main/java/wevioo/backend/`.

---

## 1. Ce que fait le serveur

| Responsabilité | Où |
| --- | --- |
| Recevoir et valider les dépôts, créer les versions | `service/DatasetService.java` |
| Parler à Airflow | `service/AirflowClient.java` |
| Recevoir les résultats du worker | `controller/PipelineController.java`, `service/PipelineIngestionService.java` |
| Évaluer les règles, corriger, construire le graphe, relayer les questions | séances 5, 7, 8, 9 |
| Protéger l'accès, pousser les événements | séances 6 et 10 |

Un serveur Spring Boot a toujours la même forme en couches : un **contrôleur** reçoit la requête HTTP, un **service** contient la logique, un **dépôt** (*repository*) lit et écrit en base, une **entité** représente une ligne de la base. Ici : `DatasetController` → `DatasetService` → `DatasetRepository` / `DatasetVersionRepository` → `Dataset` / `DatasetVersion`.

---

## 2. Le modèle de données

```
Dataset  (nom unique)
  └── 1..* DatasetVersion  (numéro de version ; unique par (jeu, numéro))
          ├── 1..* PipelineRun       (une exécution, identifiant « run-<UUID> »)
          ├── 1..* TableVersion      (une table de cette version)
          │         ├── previousTableVersion → la même table dans la version précédente
          │         └── 1 TableAnalysis ── 1..* ColumnMetric (une ligne par colonne)
          ├── 0..1 GlobalAnalysis
          └── RuleResult (résultats des règles)
```

Points à noter :
- `Dataset.name` est **unique** (`@Column(unique = true)`) : déposer deux fois le même nom ajoute une version au même jeu de données, il n'en crée pas un second.
- `DatasetVersion` a une contrainte d'unicité sur `(dataset_id, version_number)` : impossible d'avoir deux « v2 » dans le même jeu.
- `@OneToMany(cascade = ALL, orphanRemoval = true)` : supprimer un jeu supprime en cascade ses versions, leurs tables, leurs analyses. C'est ce qui rend `deleteDataset` simple.
- Une version **corrigée** garde sa parenté : `derivedFromVersionId` et `derivedFromVersionNumber`.
- Le statut de la version est un `enum RunStatus` : `PENDING`, `RUNNING`, `COMPLETED`, `PARTIAL`, `FAILED`. `isActive` (ligne 906) vaut vrai pour `PENDING` et `RUNNING` : on ne relance et on ne supprime pas une version active.

---

## 3. Le dépôt, pas à pas

### 3.1 Le contrôleur
`controller/DatasetController.java`, `POST /api/datasets/upload` : reçoit un formulaire `multipart` (fichiers + nom, domaine, objectif, grain de ligne, contexte libre). Il ne fait **rien** d'autre que rassembler les paramètres et appeler `datasetService.upload(...)`. C'est volontaire : toute la logique est dans le service, ce qui la rend testable sans HTTP.

### 3.2 Valider avant d'écrire : `prepareUpload` (ligne 478)
Pour chaque fichier, dans l'ordre :
1. `safeFilename` (ligne 931) : ne garde que le nom du fichier (`Path.of(...).getFileName()`), ce qui empêche un nom comme `../../etc/x.csv`. Un nom vide ou commençant par un point est refusé.
2. Extension `.csv` obligatoire, sinon erreur « Only .csv files are supported ».
3. Le nom `dataset_config.csv` est **réservé** (il sert au contexte, pas à une table).
4. Deux fichiers du même nom dans le même envoi sont refusés.
5. Un fichier vide est *ignoré* (listé dans `skippedFiles`), mais si **tous** sont vides, c'est une erreur.
6. Le **SHA-256** de chaque fichier est calculé (`sha256`, ligne 524) en le lisant par blocs de 64 Ko : on n'a pas besoin de garder le contenu en mémoire.

**Principe :** *tout est validé avant la moindre écriture sur le disque*. C'est ce qui garantit qu'un envoi invalide ne laisse aucune trace (vous l'avez vu avec TF1 : HTTP 400, aucun jeu de données créé).

### 3.3 Planifier : `planUpload` (ligne 302), dans une transaction
Appelé via `transactionTemplate.execute(...)` (ligne 110). Deux branches.

**Branche A — le jeu existe déjà et les fichiers sont identiques à une version** (lignes 304-333) :
- Sans `rerunExisting` : on **ne crée rien**, on renvoie la version existante avec `duplicateVersion = true` et le message « Duplicate upload detected. Existing version was not re-run. ».
- Avec `rerunExisting` : si la version est active → `ConflictException` (HTTP 409) ; sinon → `prepareRerun` (voir §5).

**Branche B — nouvelle version** (lignes 335-370) :
1. Retrouve ou crée le `Dataset` (type `CSV` ou `CSV_COLLECTION`), puis `dataset.touch()` met à jour `updatedAt`.
2. Calcule `nextVersion` = plus grand numéro existant + 1 (ou 1).
3. Calcule le dossier `versionDirectory` = `<racine>/datasets/dataset_<id>/v<n>` (ligne 786).
4. Supprime ce dossier s'il existe (il ne peut être qu'un reste d'une base réinitialisée, commentaire lignes 348-349).
5. `storeFiles` copie les fichiers ; `writeDatasetConfig` écrit `dataset_config.csv` (description et question libres, ligne `__global__` ou une ligne par table) ; `writeBusinessContext` écrit `dataset_context.json` (domaine, objectif, grain, limités à 1 000 caractères).
6. Crée la `DatasetVersion` au statut `PENDING`, avec une **signature** JSON de l'envoi (`uploadSignature` : pour chaque fichier, nom, taille, SHA-256).
7. `createPipelineRun` (ligne 465) crée un `PipelineRun` avec l'identifiant `"run-" + UUID`.
8. Si quelque chose échoue (`catch RuntimeException`), le dossier créé est supprimé : pas de fichiers orphelins.

### 3.4 Déclencher après le commit : `triggerCommittedRun` (ligne 408)
Une fois la transaction terminée, on appelle `airflowClient.triggerPipeline(...)`. Si Airflow refuse ou est injoignable, `markTriggerFailed` (ligne 437) ouvre **une nouvelle petite transaction** qui passe l'exécution et la version à `FAILED` avec un message clair, et un événement `PIPELINE_FAILED` est émis.

> **Question à vous poser :** pourquoi ne pas tout faire dans la même transaction ? Parce qu'un appel réseau lent ou en échec ne doit pas garder une transaction de base de données ouverte, et parce que le worker pourrait rappeler avant que le commit soit fini.

### 3.5 Les chemins : hôte contre conteneur
Le serveur écrit les fichiers sur *son* disque (par exemple `C:\...\Data_Quality_Wevioo\data\uploads\...`), mais le worker les lit dans *son* conteneur. `PipelinePaths.toContainerPath` (fichier `service/PipelinePaths.java`) transforme `.../Data_Quality_Wevioo/data/uploads/...` en `/opt/airflow/data/uploads/...`, parce que le dossier du worker est monté sur `/opt/airflow` dans les conteneurs. C'est ce chemin-là qui est envoyé à Airflow (`file_path`).

---

## 4. La détection de doublons : `findMatchingVersion` (ligne 540)

Idée : un envoi est un doublon si, dans une version existante, **les noms de fichiers et leurs contenus sont identiques**.

1. On construit une table `nom → sha256` des fichiers reçus (`TreeMap`, triée, donc comparaison indépendante de l'ordre).
2. Pour chaque version du jeu (de la plus récente à la plus ancienne), `versionMatchesUpload` (ligne 548) :
   - ignore une version dont le dossier n'existe plus ;
   - fait un **contrôle rapide** : mêmes noms et mêmes tailles ? sinon `false` sans calculer de hash ;
   - compare les hash stockés dans la signature de la version ;
   - pour les **anciennes versions sans hash** (créées avant cette fonction), recalcule les hash depuis les fichiers du disque, **met à jour** la signature, puis compare.

**Pourquoi le nom *et* le contenu ?** Deux fichiers peuvent avoir le même contenu mais un autre nom (une autre table), ou le même nom et un contenu différent (une nouvelle version du fichier). Seule la combinaison est un vrai doublon. Et le contrôle des tailles avant les hash évite de relire inutilement des fichiers volumineux.

---

## 5. La relance : `rerunVersion` (ligne 119)

Bouton **Retry** de l'interface. `POST /api/datasets/{id}/versions/{v}/rerun`.
1. Si la version est active → 409 « already running ».
2. Si ses fichiers ont disparu → 409 « stored files are missing ».
3. `prepareRerun` (ligne 373) : retrouve le dossier, **efface les résultats précédents** (`clearVersionResults`, ligne 391 : tables, analyse globale, résultats de règles), remet la version à `PENDING`, crée un **nouveau** `PipelineRun`.
4. Déclenche Airflow comme pour un dépôt.

**Pourquoi effacer ?** Pour que la relance parte d'un état propre et cohérent : sinon on mélangerait d'anciens résultats avec les nouveaux. Notez que la relance garde **la même version** ; seul l'identifiant d'exécution change.

Dans `clearVersionResults`, les `previousTableVersion` pointant vers les tables supprimées sont d'abord mis à `null` (lignes 394-396) : c'est pour ne pas violer une contrainte de clé étrangère.

---

## 6. La version dérivée : `createDerivedVersion` (ligne 157)

Utilisée par les **corrections** (séance 7). Même schéma qu'un dépôt, sauf que les fichiers ne viennent pas de l'utilisateur : un `DerivedFiller` (fonction passée en paramètre) écrit les fichiers corrigés dans le nouveau dossier à partir de l'ancien. La nouvelle version garde `derivedFromVersionId` et `derivedFromVersionNumber`. La version d'origine n'est jamais modifiée.

Le paramètre `DerivedFiller` est un bon exemple d'**inversion de dépendance** : `DatasetService` ne sait pas *comment* on corrige, seulement qu'on lui donne une fonction pour remplir le dossier.

---

## 7. Lire : listes et détails

- `listDatasets` (ligne 201) : pour chaque jeu, sa dernière version, le nombre de règles en échec (`RuleStatus.FAIL`), le **score pondéré** de la dernière version (`VersionDiffService.weightedScore`, sur les tables non `REMOVED`) et le nombre de tables. C'est ce qui alimente le tableau de bord.
- `getVersionDetails` (ligne 238) assemble en un seul objet : résumé de la version, analyse globale, tables, tables en échec de la dernière exécution, score de la version (`QualityScores.rowWeighted`), résumé des règles, **dimensions** de qualité. Le paramètre `includeRaw` ajoute les JSON bruts du pipeline : par défaut, l'interface n'en a pas besoin.
- Les méthodes de lecture portent `@Transactional(readOnly = true)` : une indication à la base qu'on ne modifie rien, ce qui peut l'optimiser.

---

## 8. La suppression : `deleteDataset` (ligne 276)

Dans l'ordre :
1. **Refuse** si une version est active (409).
2. Met à `null` tous les `previousTableVersion` des tables du jeu, puis `flush()` : sinon la suppression en cascade échouerait sur les clés étrangères entre versions.
3. Supprime le jeu (la cascade supprime versions, tables, analyses) puis `flush()`.
4. Supprime les **fichiers** : le dossier des dépôts, et les dossiers du worker (`storage/datasets`, `storage/json`, `storage/sweetviz`).
5. Supprime le **graphe Neo4j** : `graphStore.clearPrefix("d<id>v")`, dans un `try/catch` : si Neo4j est arrêté, ça ne doit pas empêcher la suppression (un avertissement est journalisé).

`deleteDirectory` (ligne 815) est **défensif** : il refuse tout chemin qui n'est pas *sous* la racine autorisée ou qui *égale* la racine. C'est une protection contre une erreur qui effacerait un mauvais dossier.

---

## 9. Mots à maîtriser

| Mot | Définition courte |
| --- | --- |
| Transaction | Un groupe d'opérations en base qui réussissent toutes ou échouent toutes. |
| `TransactionTemplate` | Façon *programmatique* de délimiter une transaction (ici pour pouvoir agir *après* le commit). |
| Entité JPA | Classe Java liée à une table de la base. |
| Cascade / orphanRemoval | Propager les opérations aux enfants / supprimer les enfants détachés. |
| SHA-256 | Empreinte de 256 bits d'un contenu : le moindre changement donne une empreinte totalement différente. |
| Idempotent | Refaire l'opération donne le même résultat (déposer deux fois le même fichier ne crée pas deux versions). |
| HTTP 409 | « Conflit » : la demande est valide mais l'état actuel l'interdit (version déjà en cours). |

---

## 10. Exercices

**A. Voir les fichiers d'une version (5 min).** Ouvrez `Data_Quality_Wevioo/data/uploads/datasets/`. Choisissez un dossier `dataset_<n>/v1`. Quels fichiers y trouvez-vous ? Déposez ensuite un CSV avec un **domaine** et un **objectif** : quel fichier apparaît en plus ?
*(Attendu : `dataset_context.json`.)*

**B. Provoquer un doublon (10 min).** Avec le jeton de la séance 0, déposez deux fois le même fichier sous le même nom :
`curl.exe -s -X POST http://localhost:8081/api/datasets/upload -H "Authorization: Bearer <jeton>" -F "name=essai-s1" -F "files=@mon.csv"`.
Regardez `duplicateVersion` dans la réponse. Recommencez avec `-F "rerunExisting=true"` : qu'est-ce qui change dans `pipelineRunId` ? Et `versionNumber` ?
*(Attendu : au 2ᵉ appel `duplicateVersion=true`, pas de nouvelle version ; avec `rerunExisting=true`, même version, **nouvel** identifiant d'exécution.)*

**C. Provoquer un conflit (10 min).** Déposez un fichier, puis appelez tout de suite `POST /api/datasets/<id>/versions/<v>/rerun`. Quel code HTTP et quel message ?
*(Attendu : 409 « Version 1 is already running ».)*

**D. Voir la suppression (10 min).** Supprimez un jeu de test depuis le tableau de bord. Vérifiez que son dossier `dataset_<id>` a disparu de `data/uploads/datasets/`. Que devient son graphe Neo4j (`MATCH (n) WHERE n._scope STARTS WITH 'd<id>v' RETURN count(n)`) ?

**E. Lire deux tests (15 min).** Dans `backend/src/test/java/wevioo/backend/api/ApiIntegrationTest.java`, lisez `duplicateDetectionUsesContentHashNotJustSize` (ligne 121) et `emptyFilesAreReportedInsteadOfSilentlyDropped` (ligne 216). Lisez comment chaque test construit ses faux fichiers et ce qu'il vérifie : c'est la meilleure façon de comprendre le comportement attendu. Le premier test prouve ce que dit le §4 : deux fichiers de même taille mais de contenu différent ne sont **pas** des doublons.

---

## 11. Questions de contrôle

1. Quelles couches traverse un dépôt, du contrôleur à la base ?
2. Pourquoi valider *tous* les fichiers avant d'écrire quoi que ce soit ?
3. Que contient la « signature » d'un dépôt, et à quoi sert-elle ?
4. Pourquoi comparer d'abord les tailles puis les hash ?
5. Pourquoi déclencher Airflow hors de la transaction ?
6. Que se passe-t-il si Airflow est arrêté au moment du dépôt ?
7. Quelle est la différence entre déposer deux fois le même fichier et le relancer avec `rerunExisting` ?
8. Que fait `clearVersionResults` et pourquoi ?
9. En quoi une version dérivée diffère-t-elle d'un dépôt ?
10. Pourquoi `deleteDataset` remet-il d'abord les `previousTableVersion` à `null` ?
11. Que protège `deleteDirectory` ?
12. Comment le serveur sait-il où le worker doit lire les fichiers ?

### Corrections
1. Contrôleur → service → dépôts (repositories) → entités / base.
2. Pour qu'un envoi invalide ne laisse aucune trace et ne crée pas de version à moitié.
3. Nombre de fichiers, taille totale et, par fichier, nom, taille, SHA-256. Elle sert à détecter les doublons sans relire les fichiers.
4. Une taille différente suffit à conclure « pas un doublon » sans calculer d'empreinte ; les hash ne sont comparés que si tailles et noms concordent.
5. Pour ne pas garder une transaction ouverte pendant un appel réseau, et pour que la version soit déjà enregistrée quand le worker rappelle.
6. Le dépôt est enregistré, mais `markTriggerFailed` passe la version à `FAILED` avec « Could not start the analysis in Airflow… » ; Retry fonctionnera quand Airflow reviendra.
7. Sans `rerunExisting`, rien ne se passe, la version existante est renvoyée. Avec, la même version est relancée : résultats effacés, nouvelle exécution, nouvel identifiant.
8. Supprime les tables, l'analyse globale et les résultats de règles de la version pour repartir d'un état cohérent.
9. Les fichiers sont produits par une fonction de correction à partir d'une autre version, et la parenté est enregistrée ; ils ne viennent pas d'un envoi utilisateur.
10. Pour ne pas violer les clés étrangères entre versions au moment de la suppression en cascade.
11. Qu'on ne supprime rien hors de la racine autorisée, ni la racine elle-même.
12. `PipelinePaths.toContainerPath` convertit le chemin du serveur en chemin vu par le conteneur (`/opt/airflow/...`) et l'envoie dans `conf.file_path`.

---

**Prochaine séance :** le pipeline Airflow côté worker (`runners.py`, les 9 phases, les manifestes).
