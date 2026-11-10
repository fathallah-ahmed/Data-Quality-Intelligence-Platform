# Séance 8 — Le graphe de connaissances (Neo4j)

**Objectif :** comprendre comment des tables CSV deviennent un graphe, **sans qu'un modèle de langage invente la structure**, comment on gère les conflits, et pourquoi chaque version a son propre graphe.

**Durée :** 2 h · **Prérequis :** séances 3 (rôles, relations) et 7.

Fichiers (sous `backend/src/main/java/wevioo/backend/`) :
- `graph/GraphModeler.java` (880 lignes) : **décide** de la structure.
- `graph/GraphBuilder.java` (237) : **construit** les nœuds et relations depuis les lignes.
- `graph/Neo4jGraphStore.java` : **écrit** dans Neo4j.
- `graph/GraphNamer.java` : propose des **noms** (le seul rôle du modèle).
- `service/GraphService.java` (205) : le chef d'orchestre.
- `controller/GraphController.java` : les routes.

---

## 1. Vocabulaire du graphe

| Mot | Sens | Exemple |
| --- | --- | --- |
| Nœud | une chose | un client, un compte |
| Étiquette (*label*) | le type d'un nœud | `Customer`, `Account` |
| Relation | un lien orienté entre deux nœuds | `(Customer)-[:HAS_ACCOUNT]->(Account)` |
| Propriété | une valeur portée par un nœud | `email`, `balance` |
| Cypher | le langage de requête de Neo4j | `MATCH (c:Customer) RETURN c` |

Idée : une table relationnelle cache ses liens dans des colonnes de références (`customer_id`). Un graphe les rend **explicites**, donc on peut suivre des chemins sans jointure.

---

## 2. Le principe

> La structure du graphe vient **des données et des rôles de colonnes** (séance 3). Le modèle de langage peut seulement **renommer** des étiquettes et décrire des nœuds, jamais changer la structure.

`GraphModeler` (en-tête) : *« Every choice rests on the column roles the analysis found and on what the values really do; a model is never asked to invent structure. »* Et `GraphNamer` : *« it may only rename labels and relationship types, and describe each node »*.

---

## 3. Le parcours

```
GET  .../graph/schema   → GraphService.schema     : propose le modèle (nœuds, relations, constats)
POST .../graph/build    → GraphService.build      : construit dans Neo4j
GET  .../graph          → GraphService.last       : dernier résultat de construction
GET  .../graph/status   → GraphService.status     : Neo4j est-il prêt ? autres graphes présents ?
DELETE .../graph        → GraphService.delete     : supprime le graphe de cette version
```

**`GraphService.load`** lit, pour chaque table de la version (sans les `REMOVED`) : ses colonnes avec **rôle, confiance, nombre de valeurs distinctes, nombre de manquants**, son fichier, ses lignes, et les **relations** mesurées par le worker (séance 3). Si aucune table n'est analysée, erreur : « nothing to build a graph from ».

Puis `GraphModeler.propose` décide (§4) ; éventuellement `GraphNamer.refine` renomme avec le modèle ; l'utilisateur relit ; `GraphBuilder.build` construit (§5).

---

## 4. Comment la structure est décidée : `GraphModeler.propose` (ligne 69)

### 4.1 Une clé pour chaque table
`pickKey` (ligne 578) :
1. Prend la colonne de rôle `IDENTIFIER` ayant la plus haute confiance.
2. Sinon, une colonne non-`REFERENCE`, **sans manquant**, dont toutes les valeurs sont distinctes (constat `KEY_BY_VALUES`).
3. Sinon, **pas de clé** (avertissement `NO_KEY` : chaque ligne devient son propre nœud, et on ne pourra pas l'associer à d'autres tables).

### 4.2 Les clés composées (`compositeKeys`, ligne 262)
Une table sans identifiant peut quand même être identifiée par **une combinaison de références** : `order_items` par (`order_id`, `item_id`). Le code essaie toutes les paires puis tous les triplets parmi les colonnes de référence (au plus 4 colonnes candidates), et retient la **plus petite combinaison qui ne se répète jamais** (constat `COMPOSITE_KEY`). Au plus 1 000 000 de lignes examinées.

### 4.3 Les liens par position (`positionalLinks`, ligne 449)
Une colonne dont ≥ **95 %** des valeurs sont des **numéros de ligne** d'un autre fichier (sans identifiant) relie les lignes de ce fichier ; la ligne `N` s'appelle `row-N`.

### 4.4 Pour chaque table : quoi devient quoi
Dans l'ordre (lignes 86-212) :

1. **Clés étrangères** : les relations mesurées par le worker dont la colonne source n'est pas la clé. Si la colonne cible **n'est pas la clé** de la table cible, aucune relation n'est tracée (avertissement `RELATION_NOT_USED`).
2. **Références non reliées** : les colonnes de rôle `REFERENCE` sans relation trouvée deviennent des **nœuds dérivés**. Exemple : `customer_id` dans `accounts` (30 valeurs distinctes sur 100 lignes) crée un nœud `Customer`.
3. **Auto-références** (`selfReferences`) : une colonne dont les valeurs sont les identifiants **de la même table** (un `manager_id` dans `staffs`) devient une **relation entre deux nœuds de ce type**, pas un nœud (constat `SELF_REFERENCE`).
4. **Table de liaison** (lignes 126-141) : une table **sans clé**, avec **exactement deux colonnes**, toutes deux références, devient **une relation** et n'a pas de nœud propre (constat `LINK_TABLE`). Exemple : `friends(user, user)`.
5. **Propriétés : à qui appartient chaque colonne ?** (`ownerOf`, ligne 618). Pour chaque colonne « ordinaire », le code cherche une référence qui la **détermine** : quand la référence se répète, la colonne garde-t-elle la même valeur ? Conditions :
   - au moins **3** valeurs répétées observées (`MIN_EVIDENCE`) ;
   - un accord d'au moins **75 %** (`DEPENDENCY_SHARE`) : volontairement pas 100 %, car dans des données sales un client a deux orthographes d'un courriel, et cela doit apparaître comme un **conflit** et non faire disparaître le lien ;
   - un accord supérieur de **0,3** au hasard (`CHANCE_MARGIN`) : une colonne identique sur 95 % des lignes s'accorde avec elle-même 90 % du temps, ce qui ne dit rien sur la clé ;
   - en cas d'égalité, la référence qui a **le moins de valeurs distinctes**.
   Si un propriétaire est trouvé, la colonne devient une **propriété de ce nœud dérivé** (le `customer_name` décrit le client) ; sinon, une propriété de la ligne.
6. **Relations** créées : `HAS_<ÉTIQUETTE>` du nœud dérivé vers le nœud de la ligne ; relations par clé étrangère ; relations par position ; auto-références.

### 4.5 Les colonnes « promouvables »
Une colonne de rôle `LOCATION`, `CATEGORY` ou `STATUS` avec 2 à 100 valeurs distinctes peut devenir **un nœud à elle** (un pays, une catégorie) si l'utilisateur le demande (`promote`). Elle est listée comme `promotable` dans le modèle.

### 4.6 Le filet de sécurité
Après coup, toute relation qui pointerait vers un nœud inexistant est **retirée** (avertissement), puis `addConnectivityFindings` signale les tables reliées à rien.

### 4.7 Les noms
`label(name, table)` (ligne 820) construit une étiquette sûre : on retire les mots de fichier (`table`, `csv`…) ou de clé (`id`, `ref`…), on met au singulier, on met en `CamelCase`, on coupe à 40 caractères, on préfixe `N` si le nom ne commence pas par une lettre. C'est important : une **étiquette ne peut pas être passée en paramètre** dans Cypher, elle est écrite dans la requête ; elle doit donc être **assainie**. `GraphNamer` valide aussi les noms que propose le modèle (`^[A-Z][A-Za-z0-9]{0,39}$` pour une étiquette, `^[A-Z][A-Z0-9_]{0,50}$` pour un type de relation).

---

## 5. La construction : `GraphBuilder.build`

1. **`store.clear(scope)`** : une construction **remplace** la précédente de cette version (rien n'est laissé).
2. **Une passe sur chaque fichier**, ligne par ligne (jamais tout en mémoire). Pour chaque ligne et chaque type de nœud de cette table : calcule la **clé** ; une clé vide → ligne **ignorée** (constat `BLANK_KEY`). Sinon retrouve ou crée l'agrégat du nœud (`NodeAgg`) : **un nœud par clé**, quel que soit le nombre de lignes qui le mentionnent (`agg.rows++`). Au plus **500 000 nœuds** (sinon erreur).
3. Pour chaque propriété, `agg.observe` collecte les **valeurs distinctes vues** (au plus 5).
4. **Relations :** `from → to`, dédupliquées (`pairKeys`).
5. **Écriture des nœuds** : `properties` (ligne ~160) :
   - **une seule valeur** vue → propriété normale (les colonnes `AMOUNT` / `QUANTITY` deviennent des **nombres**) ;
   - **plusieurs valeurs** → `<colonne>__candidates` (liste) et **aucune n'est choisie** ; le nœud reçoit `_conflict = true` et `_conflicts = [colonnes]` ; constat `PROPERTY_CONFLICT` avec exemples ;
   - toujours : `_rows` (nombre de lignes qui mentionnent ce nœud) et `name` (la clé, si pas de propriété `name`).
6. **Écriture des relations** : seulement si **les deux extrémités existent**. Une extrémité **cible** inexistante : la paire est ignorée en silence ; une extrémité **source** inexistante (une commande qui référence un client absent) : comptée comme **orpheline** → constat `MISSING_TARGET` (jamais « silencieusement abandonnée », commentaire de classe).
7. Constat final `BUILT` : « 548 node(s) and 348 relationship(s) written to Neo4j. »

### 5.1 L'écriture dans Neo4j : `Neo4jGraphStore`
- `UNWIND $rows AS row MERGE (n:`Label` {_scope: $scope, key: row.key}) SET n += row.props` : **`MERGE`** crée le nœud s'il n'existe pas, le met à jour sinon : l'écriture est **idempotente**.
- Relations : `MATCH` des deux extrémités **dans le même `_scope`**, puis `MERGE (a)-[r:TYPE]->(b) SET r._scope = $scope`.
- Un **index** `(n._scope, n.key)` est créé pour chaque étiquette.

### 5.2 Le périmètre `_scope`
`GraphService.scope(datasetId, versionId)` = `"d" + jeu + "v" + version` (par exemple `d2v2`). **Tout** nœud et toute relation portent `_scope`. C'est ce qui permet à plusieurs versions et plusieurs jeux de cohabiter dans la **même base** sans se mélanger, et ce qui permet de **supprimer** un graphe (`clear(scope)` → `DELETE` de tout ce qui a ce `_scope`) ou tous ceux d'un jeu (`clearPrefix("d<id>v")`, utilisé par `deleteDataset`).

> **Conséquence pratique :** dans Neo4j Browser, une requête sans filtre `_scope` **mélange toutes les versions**. C'est ce que j'avais vu avec 10 059 nœuds.

### 5.3 État et suppression
- `status` : Neo4j répond-il (`store.ping()`) ? quelle est l'adresse du Browser pour l'utilisateur ? combien d'**autres** graphes existent (`otherGraphs`) ?
- `delete` : `clear(scope)` puis suppression du fichier `graph_build.json` de la version ; les CSV ne sont pas touchés.
- Le résultat de chaque construction est écrit dans `graph_build.json` **dans le dossier de la version** : c'est ce que relit `GET .../graph`.

---

## 6. Un exemple réel : `customer-accounts`

J'ai construit ce graphe (348 comptes). Ce que `propose` a décidé :

| Élément | Décision | Pourquoi |
| --- | --- | --- |
| `Account` | clé `account_id`, propriétés `country`, `balance` | `account_id` : rôle `IDENTIFIER` |
| `Customer` | nœud **dérivé**, clé `customer_id`, propriétés `customer_name`, `email` | `customer_id` est une `REFERENCE` qui se répète ; `customer_name` et `email` gardent la même valeur quand elle se répète |
| `HAS_ACCOUNT` | `Customer → Account` | « chaque ligne de `accounts` appartient au Customer nommé par `customer_id` » |
| constat `conflict` | `customer_id → email` : **16 des 92** valeurs répétées de `customer_id` viennent avec des courriels différents | `DEPENDENCY` : le graphe garde toutes les valeurs et n'en choisit aucune |
| `promotable` | `country` (3 valeurs distinctes) | pourrait devenir un nœud |

Résultat écrit : **348 `Account`, 200 `Customer`, 348 relations**. Les 16 clients en conflit ont `_conflict = true` et une propriété `email__candidates` avec les deux courriels (par exemple `c88@mail.com` et `c88.mail.com`).

---

## 7. Mots à maîtriser

| Mot | Définition courte |
| --- | --- |
| Nœud dérivé | Nœud créé pour une chose qu'une colonne de référence nomme (le client dans une table de comptes). |
| Clé composée | Identifiant formé de plusieurs colonnes. |
| Table de liaison | Table qui ne fait que lier deux choses ; elle devient une relation. |
| Auto-référence | Colonne qui pointe vers une autre ligne de la même table. |
| Conflit | Plusieurs valeurs différentes pour la même propriété d'un même nœud. |
| `MERGE` | « Crée s'il n'existe pas, sinon réutilise » : écriture idempotente. |
| `_scope` | Périmètre `d<jeu>v<version>` porté par tout élément du graphe. |
| Orpheline | Référence vers une chose qui n'existe pas. |

---

## 8. Exercices

**A. Voir le modèle proposé (10 min).** Avec le jeton de la séance 0 :
```powershell
curl.exe -s -H "Authorization: Bearer <jeton>" "http://localhost:8081/api/datasets/2/versions/2/graph/schema"
```
Retrouvez les nœuds, les relations et le constat `DEPENDENCY`. Comparez à l'onglet **Graph** de l'interface.

**B. Construire et regarder (15 min).** Dans Neo4j Browser (`http://localhost:7474`), lancez :
```cypher
MATCH (c:Customer {_scope: 'd2v2', _conflict: true})-[r:HAS_ACCOUNT]->(a:Account {_scope: 'd2v2'}) RETURN c, r, a LIMIT 15;
```
Ouvrez un client : trouvez `email__candidates`. Puis lancez la même requête **sans** `_scope` : que se passe-t-il ?

**C. Idempotence (10 min).** Reconstruisez le graphe deux fois de suite (`POST .../graph/build`). Le nombre de nœuds change-t-il ? *(Attendu : non, `store.clear` puis `MERGE`.)*

**D. Supprimer (10 min).** Cliquez **Delete this graph**, puis relancez la requête B : plus rien. Les CSV de la version sont-ils toujours là ?

**E. Un cas sans identifiant (20 min).** Reproduisez mon essai, que j'ai exécuté pour de vrai :
- `orders.csv` : colonnes `order_id` (`O1` à `O10`) et `order_date`, 10 lignes ;
- `order_items.csv` : colonnes `order_id`, `item_id`, `quantity`, **40 lignes** : chacune des 10 commandes avec les 4 articles `P1` à `P4` (le couple `order_id`, `item_id` ne se répète jamais, mais aucune colonne seule n'est unique).
Déposez les deux fichiers dans un jeu `essai-composite`, attendez la fin, puis ouvrez l'onglet **Graph** (ou `GET .../graph/schema`).

*Résultat que j'ai obtenu :* nœuds `Order` (clé `order_id`, propriété `order_date`) et `OrderItem` (clé **`order_id + item_id`**, propriétés `item_id` et `quantity`) ; relation `HAS_ORDER_ITEM : Order → OrderItem` ; constat `COMPOSITE_KEY` : *« No single column identifies the rows of order_items, but order_id and item_id together never repeat, so together they identify a row. »*

Pourquoi ça marche : `order_id` et `item_id` sont chacune une `REFERENCE` (elles se répètent), la table a une 3ᵉ colonne (`quantity`), et la **paire** ne se répète jamais. Que se passe-t-il si vous supprimez la colonne `quantity` ? *(Indice : `table.columns().size() <= candidates.size()`, ligne 275 de `GraphModeler`, et la règle de la table de liaison du §4.4.)*

**F. Lire un test (15 min).** `backend/src/test/java/wevioo/backend/graph/GraphModelTest.java` : choisissez trois tests et dites quelle règle de §4 chacun vérifie.

---

## 9. Questions de contrôle

1. Quelle est la seule chose que le modèle de langage peut faire dans le graphe ?
2. Comment `GraphModeler` choisit-il la clé d'une table ?
3. Qu'est-ce qu'un nœud dérivé ? Donnez un exemple.
4. Pourquoi le seuil d'accord n'est-il pas 100 % ?
5. À quoi sert la « marge sur le hasard » ?
6. Que devient une table avec deux colonnes de références et pas de clé ?
7. Comment une table sans identifiant est-elle identifiée ?
8. Que fait le graphe quand les lignes d'une même clé se contredisent ?
9. Pourquoi une étiquette doit-elle être assainie ?
10. Pourquoi utiliser `MERGE` ?
11. Quel rôle joue `_scope` et que se passe-t-il sans lui dans une requête ?
12. Que devient une référence vers une chose qui n'existe pas ?

### Corrections
1. Proposer des noms pour les étiquettes et les types de relation, et décrire les nœuds ; il ne peut ni créer, ni supprimer, ni relier.
2. Colonne `IDENTIFIER` de plus haute confiance ; sinon colonne remplie et distincte sur toutes les lignes ; sinon pas de clé (avertissement).
3. Un nœud pour une chose que nomme une colonne de référence répétée : `Customer` créé depuis `customer_id` dans une table de comptes.
4. Dans des données sales, un client peut avoir deux orthographes d'un courriel : à 100 %, le lien serait perdu ; à 75 %, il est gardé et l'écart apparaît comme un conflit.
5. À éviter qu'une colonne presque constante soit rattachée à n'importe quelle clé : l'accord doit dépasser le hasard de 0,3.
6. Une **relation** sans nœud propre (`LINK_TABLE`).
7. Par la plus petite combinaison de colonnes de référence qui ne se répète jamais (clé composée).
8. Il garde toutes les valeurs (`<col>__candidates`), marque le nœud `_conflict`, et n'en choisit aucune.
9. Parce qu'une étiquette est écrite dans la requête Cypher (elle ne peut pas être un paramètre) : un nom non assaini permettrait d'injecter du Cypher.
10. Pour que l'écriture soit idempotente : rejouer ne crée pas de doublons.
11. Il porte le périmètre `d<jeu>v<version>` : isole les versions, permet de supprimer un graphe. Sans lui, la requête mélange toutes les versions.
12. Elle n'est pas dessinée ; elle est comptée et signalée par un constat `MISSING_TARGET` avec des exemples.

---

**Prochaine séance :** les questions en langage naturel (GraphRAG) et leurs garde-fous.
