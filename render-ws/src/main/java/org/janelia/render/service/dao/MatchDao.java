package org.janelia.render.service.dao;

import com.mongodb.MongoClient;
import com.mongodb.QueryOperators;
import com.mongodb.bulk.BulkWriteResult;
import com.mongodb.client.AggregateIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.ReplaceOneModel;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.WriteModel;
import com.mongodb.client.result.DeleteResult;

import java.io.IOException;
import java.io.OutputStream;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.bson.Document;
import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.match.MatchCollectionId;
import org.janelia.alignment.match.MatchCollectionMetaData;
import org.janelia.alignment.util.ProcessTimer;
import org.janelia.render.service.model.ObjectNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Data access object for Match database.
 *
 * @author Eric Trautman
 */
public class MatchDao {

    public static final String MATCH_DB_NAME = "match";

    public static MatchDao build()
            throws UnknownHostException {
        final MongoClient mongoClient = SharedMongoClient.getInstance();
        return new MatchDao(mongoClient);
    }

    private final MongoDatabase matchDatabase;

    public MatchDao(final MongoClient client) {
        matchDatabase = client.getDatabase(MATCH_DB_NAME);
    }

    /**
     * @return list of match collection metadata.
     */
    public List<MatchCollectionMetaData> getMatchCollectionMetaData()
            throws IllegalArgumentException {

        final List<MatchCollectionMetaData> list = new ArrayList<>();
        for (final String collectionName : matchDatabase.listCollectionNames()) {
            if (! collectionName.startsWith("system.")) {
                list.add(
                        new MatchCollectionMetaData(
                                MatchCollectionId.fromDbCollectionName(collectionName),
                                matchDatabase.getCollection(collectionName).count()));
            }
        }

        return list;
    }

    /**
     * @return list of distinct pGroupIds in the specified collection.
     */
    public List<String> getDistinctPGroupIds(final MatchCollectionId collectionId)
            throws IllegalArgumentException {
        return getDistinctIdsForField(collectionId, "pGroupId");
    }

    /**
     * @return list of distinct qGroupIds in the specified collection.
     */
    public List<String> getDistinctQGroupIds(final MatchCollectionId collectionId)
            throws IllegalArgumentException {
        return getDistinctIdsForField(collectionId, "qGroupId");
    }

    /**
     * @return list of distinct p and q groupIds in the specified collection.
     */
    public List<String> getDistinctGroupIds(final MatchCollectionId collectionId)
            throws IllegalArgumentException {
        final Set<String> groupIds = new TreeSet<>();
        groupIds.addAll(getDistinctPGroupIds(collectionId));
        groupIds.addAll(getDistinctQGroupIds(collectionId));
        return new ArrayList<>(groupIds);
    }

    /**
     * Finds p sections that have multiple (split) cross layer consensus set match pairs.
     *
     * @return list of pGroupIds for pGroupId/qGroupId combinations that have more than one match pair
     *         in the specified collection.
     */
    public List<String> getMultiConsensusPGroupIds(final MatchCollectionId collectionId)
            throws IllegalArgumentException {

        final MongoCollection<Document> matchCollection = getExistingCollection(collectionId);

        // db.<matchCollection>.aggregate(
        //     [
        //         { "$match": { "consensusSetData": { "$exists": true } } },
        //         { "$group": { "_id": { "pGroupId": "$pGroupId" } } }
        //     ]
        // )

        final List<Document> pipeline = new ArrayList<>();
        pipeline.add(new Document("$match",
                                  new Document("consensusSetData",
                                               new Document(QueryOperators.EXISTS, true))));
        pipeline.add(new Document("$group",
                                  new Document("_id",
                                               new Document("pGroupId", "$pGroupId"))));

        if (LOG.isDebugEnabled()) {
            LOG.debug("getMultiConsensusPGroupIds: running {}.aggregate({})",
                      MongoUtil.fullName(matchCollection),
                      MongoUtil.toJson(pipeline));
        }

        // sort and reduce to distinct set of group ids here instead of in pipeline
        final TreeSet<String> pGroupIdsWithMultiplePairs = new TreeSet<>();

        // mongodb java 3.0 driver notes:
        // -- need to set cursor batchSize to prevent NPE from cursor creation
        final AggregateIterable<Document> iterable = matchCollection.aggregate(pipeline).batchSize(1);
        try (MongoCursor<Document> cursor = iterable.iterator()) {
            while (cursor.hasNext()) {
                final Document id = cursor.next().get("_id", Document.class);
                pGroupIdsWithMultiplePairs.add(id.getString("pGroupId"));
            }
        }

        return new ArrayList<>(pGroupIdsWithMultiplePairs);
    }

    /**
     * Finds all sections that have multiple (split) cross layer consensus set match pairs.
     *
     * @return distinct set of p and q group ids for pGroupId/qGroupId combinations that have more than one match pair
     *         in the specified collection.
     */
    public Set<String> getMultiConsensusGroupIds(final MatchCollectionId collectionId)
            throws IllegalArgumentException {

        final MongoCollection<Document> matchCollection = getExistingCollection(collectionId);

        // db.<matchCollection>.aggregate(
        //     [
        //         { "$match": { "consensusSetData": { "$exists": true } } },
        //         { "$group": { "_id": { "pGroupId": "$pGroupId", "qGroupId": "$qGroupId" } } }
        //     ]
        // )

        final List<Document> pipeline = new ArrayList<>();
        pipeline.add(new Document("$match",
                                  new Document("consensusSetData",
                                               new Document(QueryOperators.EXISTS, true))));
        pipeline.add(new Document("$group",
                                  new Document("_id",
                                               new Document("pGroupId", "$pGroupId").append("qGroupId", "$qGroupId"))));

        if (LOG.isDebugEnabled()) {
            LOG.debug("getMultiConsensusPGroupIds: running {}.aggregate({})",
                      MongoUtil.fullName(matchCollection),
                      MongoUtil.toJson(pipeline));
        }

        // sort and reduce to distinct set of group ids here instead of in pipeline
        final TreeSet<String> groupIdsWithMultiplePairs = new TreeSet<>();

        // mongodb java 3.0 driver notes:
        // -- need to set cursor batchSize to prevent NPE from cursor creation
        final AggregateIterable<Document> iterable = matchCollection.aggregate(pipeline).batchSize(1);
        try (MongoCursor<Document> cursor = iterable.iterator()) {
            while (cursor.hasNext()) {
                final Document id = cursor.next().get("_id", Document.class);
                groupIdsWithMultiplePairs.add(id.getString("pGroupId"));
                groupIdsWithMultiplePairs.add(id.getString("qGroupId"));
            }
        }

        return groupIdsWithMultiplePairs;
    }

    public void writeMatchesWithPGroup(final MatchCollectionId collectionId,
                                       final List<MatchCollectionId> mergeCollectionIdList,
                                       final String pGroupId,
                                       final OutputStream outputStream)
            throws IllegalArgumentException, IOException, ObjectNotFoundException {

        LOG.debug("writeMatchesWithPGroup: entry, collectionId={}, mergeCollectionIdList={}, pGroupId={}",
                  collectionId, mergeCollectionIdList, pGroupId);

        final List<MongoCollection<Document>> collectionList = getDistinctCollectionList(collectionId,
                                                                                         mergeCollectionIdList);
        MongoUtil.validateRequiredParameter("pGroupId", pGroupId);

        final Document query = new Document("pGroupId", pGroupId);

        writeMatches(collectionList, query, outputStream);
    }

    public void writeMatchesWithinGroup(final MatchCollectionId collectionId,
                                        final List<MatchCollectionId> mergeCollectionIdList,
                                        final String groupId,
                                        final OutputStream outputStream)
            throws IllegalArgumentException, IOException, ObjectNotFoundException {

        LOG.debug("writeMatchesWithinGroup: entry, collectionId={}, mergeCollectionIdList={}, groupId={}",
                  collectionId, mergeCollectionIdList, groupId);

        final List<MongoCollection<Document>> collectionList = getDistinctCollectionList(collectionId,
                                                                                         mergeCollectionIdList);
        MongoUtil.validateRequiredParameter("groupId", groupId);

        final Document query = new Document("pGroupId", groupId).append("qGroupId", groupId);

        writeMatches(collectionList, query, outputStream);
    }

    public void writeMatchesOutsideGroup(final MatchCollectionId collectionId,
                                         final List<MatchCollectionId> mergeCollectionIdList,
                                         final String groupId,
                                         final OutputStream outputStream)
            throws IllegalArgumentException, IOException, ObjectNotFoundException {

        LOG.debug("writeMatchesOutsideGroup: entry, collectionId={}, mergeCollectionIdList={}, groupId={}",
                  collectionId, mergeCollectionIdList, groupId);

        final List<MongoCollection<Document>> collectionList = getDistinctCollectionList(collectionId,
                                                                                         mergeCollectionIdList);
        MongoUtil.validateRequiredParameter("groupId", groupId);

        final Document query = getOutsideGroupQuery(groupId);

        writeMatches(collectionList, query, outputStream);
    }

    public List<CanvasMatches> getMatchesOutsideGroup(final MatchCollectionId collectionId,
                                                      final String groupId)
            throws IllegalArgumentException, IOException, ObjectNotFoundException {

        LOG.debug("getMatchesOutsideGroup: entry, collectionId={}, groupId={}",
                  collectionId, groupId);

        final MongoCollection<Document> collection = getExistingCollection(collectionId);

        MongoUtil.validateRequiredParameter("groupId", groupId);

        final Document query = getOutsideGroupQuery(groupId);

        return getMatches(collection, query);
    }

    public void writeMatchesBetweenGroups(final MatchCollectionId collectionId,
                                          final List<MatchCollectionId> mergeCollectionIdList,
                                          final String pGroupId,
                                          final String qGroupId,
                                          final OutputStream outputStream)
            throws IllegalArgumentException, IOException, ObjectNotFoundException {

        LOG.debug("writeMatchesBetweenGroups: entry, collectionId={}, mergeCollectionIdList={}, pGroupId={}, qGroupId={}",
                  collectionId, mergeCollectionIdList, pGroupId, qGroupId);

        final List<MongoCollection<Document>> collectionList = getDistinctCollectionList(collectionId,
                                                                                         mergeCollectionIdList);
        MongoUtil.validateRequiredParameter("pGroupId", pGroupId);
        MongoUtil.validateRequiredParameter("qGroupId", qGroupId);

        final String noTileId = "";
        final CanvasMatches normalizedCriteria = new CanvasMatches(pGroupId, noTileId, qGroupId, noTileId, null);
        final Document query = new Document(
                "pGroupId", normalizedCriteria.getpGroupId()).append(
                "qGroupId", normalizedCriteria.getqGroupId());

        writeMatches(collectionList, query, outputStream);
    }

    public void writeMatchesBetweenObjectAndGroup(final MatchCollectionId collectionId,
                                                  final List<MatchCollectionId> mergeCollectionIdList,
                                                  final String pGroupId,
                                                  final String pId,
                                                  final String qGroupId,
                                                  final OutputStream outputStream)
            throws IllegalArgumentException, IOException, ObjectNotFoundException {

        LOG.debug("writeMatchesBetweenObjectandGroup: entry, collectionId={}, mergeCollectionIdList={}, pGroupId={}, pId={}, qGroupId={}",
                  collectionId, mergeCollectionIdList, pGroupId, pId, qGroupId);

        final List<MongoCollection<Document>> collectionList = getDistinctCollectionList(collectionId,
                                                                                         mergeCollectionIdList);
        MongoUtil.validateRequiredParameter("pGroupId", pGroupId);
        MongoUtil.validateRequiredParameter("pId", pId);
        MongoUtil.validateRequiredParameter("qGroupId", qGroupId);

        final Document query = getInvolvingObjectAndGroupQuery(pGroupId, pId, qGroupId);

        writeMatches(collectionList, query, outputStream);
    }

    public CanvasMatches getMatchesBetweenObjects(final MatchCollectionId collectionId,
                                                  final String pGroupId,
                                                  final String pId,
                                                  final String qGroupId,
                                                  final String qId)
            throws IllegalArgumentException, IOException, ObjectNotFoundException {

        MongoUtil.validateRequiredParameter("pGroupId", pGroupId);
        MongoUtil.validateRequiredParameter("pId", pId);
        MongoUtil.validateRequiredParameter("qGroupId", qGroupId);
        MongoUtil.validateRequiredParameter("qId", qId);

        final CanvasMatches normalizedCriteria = new CanvasMatches(pGroupId, pId, qGroupId, qId, null);
        final Document query = new Document(
                "pGroupId", normalizedCriteria.getpGroupId()).append(
                "pId", normalizedCriteria.getpId()).append(
                "qGroupId", normalizedCriteria.getqGroupId()).append(
                "qId", normalizedCriteria.getqId());

        final MongoCollection<Document> collection = getExistingCollection(collectionId);

        int matchCount = 0;
        CanvasMatches canvasMatches = null;
        try (MongoCursor<Document> cursor = collection.find(query).iterator()) {
            if (cursor.hasNext()) {
                canvasMatches = CanvasMatches.fromJson(cursor.next().toJson());
                matchCount = canvasMatches.size();
            }
        }

        if (matchCount == 0) {
            throw new ObjectNotFoundException(collectionId + " does not contain matches between " +
                                              pId + " and " + qId);
        }

        LOG.debug("getMatchesBetweenObjects: returning {} matches for {}.find({})",
                  matchCount, collection.getNamespace().getFullName(), query.toJson());

        return canvasMatches;
    }

    public void writeMatchesBetweenObjects(final MatchCollectionId collectionId,
                                           final List<MatchCollectionId> mergeCollectionIdList,
                                           final String pGroupId,
                                           final String pId,
                                           final String qGroupId,
                                           final String qId,
                                           final OutputStream outputStream)
            throws IllegalArgumentException, IOException, ObjectNotFoundException {

        LOG.debug("writeMatchesBetweenObjects: entry, collectionId={}, mergeCollectionIdList={}, pGroupId={}, pId={}, qGroupId={}, qId={}",
                  collectionId, mergeCollectionIdList, pGroupId, pId, qGroupId, qId);

        final List<MongoCollection<Document>> collectionList = getDistinctCollectionList(collectionId,
                                                                                         mergeCollectionIdList);
        MongoUtil.validateRequiredParameter("pGroupId", pGroupId);
        MongoUtil.validateRequiredParameter("pId", pId);
        MongoUtil.validateRequiredParameter("qGroupId", qGroupId);
        MongoUtil.validateRequiredParameter("qId", qId);

        final CanvasMatches normalizedCriteria = new CanvasMatches(pGroupId, pId, qGroupId, qId, null);
        final Document query = new Document(
                "pGroupId", normalizedCriteria.getpGroupId()).append(
                "pId", normalizedCriteria.getpId()).append(
                "qGroupId", normalizedCriteria.getqGroupId()).append(
                "qId", normalizedCriteria.getqId());

        writeMatches(collectionList, query, outputStream);
    }

    public void writeMatchesInvolvingObject(final MatchCollectionId collectionId,
                                            final List<MatchCollectionId> mergeCollectionIdList,
                                            final String groupId,
                                            final String id,
                                            final OutputStream outputStream)
            throws IllegalArgumentException, IOException, ObjectNotFoundException {

        LOG.debug("writeMatchesInvolvingObject: entry, collectionId={}, mergeCollectionIdList={}, groupId={}, id={}",
                  collectionId, mergeCollectionIdList, groupId, id);

        final List<MongoCollection<Document>> collectionList = getDistinctCollectionList(collectionId,
                                                                                         mergeCollectionIdList);
        MongoUtil.validateRequiredParameter("groupId", groupId);
        MongoUtil.validateRequiredParameter("id", id);

        final Document query = getInvolvingObjectQuery(groupId, id);

        writeMatches(collectionList, query, outputStream);
    }

    public void removeMatchesInvolvingObject(final MatchCollectionId collectionId,
                                             final String groupId,
                                             final String id)
            throws IllegalArgumentException, ObjectNotFoundException {

        LOG.debug("removeMatchesInvolvingObject: entry, collectionId={}, groupId={}, id={}",
                  collectionId, groupId, id);

        final MongoCollection<Document> collection = getExistingCollection(collectionId);

        MongoUtil.validateRequiredParameter("groupId", groupId);
        MongoUtil.validateRequiredParameter("id", id);

        final Document query = getInvolvingObjectQuery(groupId, id);

        final DeleteResult result = collection.deleteMany(query);

        LOG.debug("removeMatchesInvolvingObject: removed {} matches using {}.delete({})",
                  result.getDeletedCount(), MongoUtil.fullName(collection), query.toJson());
    }

    public void removeMatchesBetweenTiles(final MatchCollectionId collectionId,
                                          final String pGroupId,
                                          final String pId,
                                          final String qGroupId,
                                          final String qId)
            throws IllegalArgumentException, ObjectNotFoundException {

        LOG.debug("removeMatchesBetweenTiles: entry, collectionId={}, pGroupId={}, pId={}, qGroupId={}, qId={}",
                  collectionId, pGroupId, pId, qGroupId, qId);

        final MongoCollection<Document> collection = getExistingCollection(collectionId);

        MongoUtil.validateRequiredParameter("pGroupId", pGroupId);
        MongoUtil.validateRequiredParameter("pId", pId);
        MongoUtil.validateRequiredParameter("qGroupId", qGroupId);
        MongoUtil.validateRequiredParameter("qId", qId);

        final CanvasMatches normalizedCriteria = new CanvasMatches(pGroupId, pId, qGroupId, qId, null);
        final Document query = new Document(
                "pGroupId", normalizedCriteria.getpGroupId()).append(
                "pId", normalizedCriteria.getpId()).append(
                "qGroupId", normalizedCriteria.getqGroupId()).append(
                "qId", normalizedCriteria.getqId());

        final DeleteResult result = collection.deleteMany(query);

        LOG.debug("removeMatchesBetweenTiles: removed {} matches using {}.delete({})",
                  result.getDeletedCount(), MongoUtil.fullName(collection), query.toJson());
    }

    public void removeMatchesBetweenGroups(final MatchCollectionId collectionId,
                                           final String pGroupId,
                                           final String qGroupId)
            throws IllegalArgumentException, ObjectNotFoundException {

        LOG.debug("removeMatchesBetweenGroups: entry, collectionId={}, pGroupId={}, qGroupId={}",
                  collectionId, pGroupId,  qGroupId);

        final MongoCollection<Document> collection = getExistingCollection(collectionId);

        MongoUtil.validateRequiredParameter("pGroupId", pGroupId);
        MongoUtil.validateRequiredParameter("qGroupId", qGroupId);

        final String noTileId = "";
        final CanvasMatches normalizedCriteria = new CanvasMatches(pGroupId, noTileId, qGroupId, noTileId, null);
        final Document query = new Document(
                "pGroupId", normalizedCriteria.getpGroupId()).append(
                "qGroupId", normalizedCriteria.getqGroupId());

        final DeleteResult result = collection.deleteMany(query);

        LOG.debug("removeMatchesBetweenGroups: removed {} matches using {}.delete({})",
                  result.getDeletedCount(), MongoUtil.fullName(collection), query.toJson());
    }

    public void removeMatchesOutsideGroup(final MatchCollectionId collectionId,
                                          final String groupId)
            throws IllegalArgumentException, ObjectNotFoundException {

        MongoUtil.validateRequiredParameter("collectionId", collectionId);
        MongoUtil.validateRequiredParameter("groupId", groupId);

        final MongoCollection<Document> collection = getExistingCollection(collectionId);
        final Document query = getOutsideGroupQuery(groupId);

        final DeleteResult result = collection.deleteMany(query);

        LOG.debug("removeMatchesOutsideGroup: removed {} matches using {}.delete({})",
                  result.getDeletedCount(), MongoUtil.fullName(collection), query.toJson());
    }

    public void saveMatches(final MatchCollectionId collectionId,
                            final List<CanvasMatches> matchesList)
            throws IllegalArgumentException {

        MongoUtil.validateRequiredParameter("collectionId", collectionId);
        MongoUtil.validateRequiredParameter("matchesList", matchesList);

        LOG.debug("saveMatches: entry, collectionId={}, matchesList.size()={}",
                  collectionId, matchesList.size());

        if (matchesList.size() > 0) {

            final MongoCollection<Document> collection =
                    matchDatabase.getCollection(collectionId.getDbCollectionName());

            ensureMatchIndexes(collection);

            final List<WriteModel<Document>> modelList = new ArrayList<>(matchesList.size());

            final UpdateOptions upsertOption = new UpdateOptions().upsert(true);
            Document filter;
            Document matchesObject;
            for (final CanvasMatches canvasMatches : matchesList) {
                canvasMatches.normalize();
                filter = new Document(
                        "pGroupId", canvasMatches.getpGroupId()).append(
                        "pId", canvasMatches.getpId()).append(
                        "qGroupId", canvasMatches.getqGroupId()).append(
                        "qId", canvasMatches.getqId());
                matchesObject = Document.parse(canvasMatches.toJson());
                modelList.add(new ReplaceOneModel<>(filter, matchesObject, upsertOption));
            }

            final BulkWriteResult result = collection.bulkWrite(modelList, MongoUtil.UNORDERED_OPTION);

            if (LOG.isDebugEnabled()) {
                final String bulkResultMessage = MongoUtil.toMessage("matches", result, matchesList.size());
                LOG.debug("saveMatches: {} using {}.initializeUnorderedBulkOp()",
                          bulkResultMessage, MongoUtil.fullName(collection));
            }
        }
    }

    public void removeAllMatches(final MatchCollectionId collectionId)
            throws IllegalArgumentException, IOException, ObjectNotFoundException {

        LOG.debug("removeAllMatches: entry, collectionId={}", collectionId);

        MongoUtil.validateRequiredParameter("collectionId", collectionId);

        final MongoCollection<Document> collection = getExistingCollection(collectionId);

        collection.drop();
    }

    /**
     * Renames the specified match collection.
     *
     * @param  fromCollectionId  original match collection.
     * @param  toCollectionId    new match collection.
     *
     * @throws IllegalArgumentException
     *   if the new match collection already exists or
     *   the original match collection cannot be renamed for any other reason.
     *
     * @throws ObjectNotFoundException
     *   if the original match collection does not exist.
     */
    public void renameMatchCollection(final MatchCollectionId fromCollectionId,
                                      final MatchCollectionId toCollectionId)
            throws IllegalArgumentException, ObjectNotFoundException {

        MongoUtil.validateRequiredParameter("fromCollectionId", fromCollectionId);
        MongoUtil.validateRequiredParameter("toCollectionId", toCollectionId);

        final String fromCollectionName = fromCollectionId.getDbCollectionName();
        final boolean fromCollectionExists = MongoUtil.exists(matchDatabase, fromCollectionName);
        if (! fromCollectionExists) {
            throw new ObjectNotFoundException(fromCollectionId + " does not exist");
        }

        final String toCollectionName = toCollectionId.getDbCollectionName();
        final boolean toCollectionExists = MongoUtil.exists(matchDatabase, toCollectionName);
        if (toCollectionExists) {
            throw new IllegalArgumentException(toCollectionId + " already exists");
        }

        MongoUtil.renameCollection(matchDatabase, fromCollectionName, toCollectionName);
    }

    private MongoCollection<Document> getExistingCollection(final MatchCollectionId collectionId) {
        return MongoUtil.getExistingCollection(matchDatabase, collectionId.getDbCollectionName());
    }

    private List<MongoCollection<Document>> getDistinctCollectionList(final MatchCollectionId collectionId,
                                                                      final List<MatchCollectionId> mergeCollectionIdList) {

        MongoUtil.validateRequiredParameter("collectionId", collectionId);

        final Set<MatchCollectionId> collectionIdSet = new HashSet<>();
        final List<MongoCollection<Document>> collectionList = new ArrayList<>();

        collectionIdSet.add(collectionId);
        collectionList.add(getExistingCollection(collectionId));

        if ((mergeCollectionIdList != null) && (mergeCollectionIdList.size() > 0)) {
            for (final MatchCollectionId mergeCollectionId : mergeCollectionIdList) {
                if (collectionIdSet.add(mergeCollectionId)) {
                    collectionList.add(getExistingCollection(mergeCollectionId));
                } else {
                    LOG.warn("filtered duplicate collection id {}", mergeCollectionId);
                }
            }
        }

        return collectionList;
    }

    private List<String> getDistinctIdsForField(final MatchCollectionId collectionId,
                                                final String fieldName) {

        MongoUtil.validateRequiredParameter("collectionId", collectionId);

        final List<String> distinctIds = new ArrayList<>(8096);

        final MongoCollection<Document> collection = getExistingCollection(collectionId);

        try (MongoCursor<String> cursor = collection.distinct(fieldName, String.class).iterator()) {
            while (cursor.hasNext()) {
                distinctIds.add(cursor.next());
            }
        }

        return distinctIds;
    }

    private List<CanvasMatches> getMatches(final MongoCollection<Document> collection,
                                           final Document query)
            throws IOException {

        final List<CanvasMatches> canvasMatchesList = new ArrayList<>();

        try (MongoCursor<Document> cursor = collection.find(query).projection(EXCLUDE_MONGO_ID_KEY).iterator()) {
            while (cursor.hasNext()) {
                canvasMatchesList.add(CanvasMatches.fromJson(cursor.next().toJson()));
            }
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("getMatches: wrote data for {} matches returned by {}.find({},{})",
                      canvasMatchesList.size(), MongoUtil.fullName(collection), query.toJson(), EXCLUDE_MONGO_ID_KEY_JSON);
        }

        return canvasMatchesList;
    }

    private void writeMatches(final List<MongoCollection<Document>> collectionList,
                              final Document query,
                              final OutputStream outputStream)
            throws IOException {

        if (collectionList.size() > 1) {

            writeMergedMatches(collectionList, query, outputStream);

        } else {

            final MongoCollection<Document> collection = collectionList.get(0);

            final ProcessTimer timer = new ProcessTimer();

            outputStream.write(OPEN_BRACKET);

            int count = 0;
            try (MongoCursor<Document> cursor = collection.find(query).projection(EXCLUDE_MONGO_ID_KEY).sort(MATCH_ORDER_BY).iterator()) {

                Document document;
                while (cursor.hasNext()) {

                    if (count > 0) {
                        outputStream.write(COMMA_WITH_NEW_LINE);
                    }

                    document = cursor.next();
                    outputStream.write(document.toJson().getBytes());
                    count++;

                    if (timer.hasIntervalPassed()) {
                        LOG.debug("writeMatches: data written for {} matches", count);
                    }
                }
            }

            outputStream.write(CLOSE_BRACKET);

            if (LOG.isDebugEnabled()) {
                LOG.debug("writeMatches: wrote data for {} matches returned by {}.find({},{}), elapsedSeconds={}",
                          count, MongoUtil.fullName(collection), query.toJson(), EXCLUDE_MONGO_ID_KEY_JSON, timer.getElapsedSeconds());
            }
        }
    }

    private void writeMergedMatches(final List<MongoCollection<Document>> collectionList,
                                    final Document query,
                                    final OutputStream outputStream)
            throws IOException {

        // exclude mongo id from results
        final ProcessTimer timer = new ProcessTimer();

        outputStream.write(OPEN_BRACKET);

        int count = 0;

        final int numberOfCollections = collectionList.size();
        final List<MongoCursor<Document>> cursorList = new ArrayList<>(numberOfCollections);
        final List<CanvasMatches> matchesList = new ArrayList<>(numberOfCollections);

        try {

            int numberOfCompletedCursors = 0;
            MongoCollection<Document> collection;
            for (int i = 0; i < numberOfCollections; i++) {
                collection = collectionList.get(i);
                cursorList.add(collection.find(query).projection(EXCLUDE_MONGO_ID_KEY).sort(MATCH_ORDER_BY).iterator());
                matchesList.add(null);
                numberOfCompletedCursors += updateMatches(cursorList, matchesList, i);
            }

            if (numberOfCompletedCursors > 0) {
                removeCompletedCursors(cursorList, matchesList);
            }

            CanvasMatches mergedMatches;
            while (matchesList.size() > 0) {
                if (count > 0) {
                    outputStream.write(COMMA_WITH_NEW_LINE);
                }

                mergedMatches = getNextMergedMatches(cursorList, matchesList);

                outputStream.write(mergedMatches.toJson().getBytes());
                count++;

                if (timer.hasIntervalPassed()) {
                    LOG.debug("writeMergedMatches: data written for {} matches", count);
                }
            }

        } finally {

            for (final MongoCursor<Document> cursor : cursorList) {
                if (cursor != null) {
                    try {
                        cursor.close();
                    } catch (final Throwable t) {
                        LOG.error("failed to close cursor, ignoring exception", t);
                    }
                }
            }

        }

        outputStream.write(CLOSE_BRACKET);

        if (LOG.isDebugEnabled()) {
            final StringBuilder collectionNames = new StringBuilder(512);
            for (int i = 0; i < collectionList.size(); i++) {
                if (i > 0) {
                    collectionNames.append('|');
                }
                collectionNames.append(MongoUtil.fullName(collectionList.get(i)));
            }
            LOG.debug("writeMergedMatches: wrote data for {} matches returned by {}.find({},{}).sort({}), elapsedSeconds={}",
                      count, collectionNames, query.toJson(), EXCLUDE_MONGO_ID_KEY_JSON, MATCH_ORDER_BY_JSON, timer.getElapsedSeconds());
        }
    }

    private CanvasMatches getNextMergedMatches(final List<MongoCursor<Document>> cursorList,
                                               final List<CanvasMatches> matchesList) {

        int numberOfCompletedCursors = 0;
        int nextMatchesIndex = 0;
        CanvasMatches nextMatches = matchesList.get(nextMatchesIndex);
        CanvasMatches matches;
        int comparisonResult;
        for (int i = 1; i < matchesList.size(); i++) {
            matches = matchesList.get(i);
            comparisonResult = matches.compareTo(nextMatches);
            if (comparisonResult == 0) {
                nextMatches.append(matches.getMatches());
                numberOfCompletedCursors += updateMatches(cursorList, matchesList, i);
            } else if (comparisonResult < 0) {
                nextMatchesIndex = i;
                nextMatches = matches;
            }
        }

        numberOfCompletedCursors += updateMatches(cursorList, matchesList, nextMatchesIndex);

        if (numberOfCompletedCursors > 0) {
            removeCompletedCursors(cursorList, matchesList);
        }

        return nextMatches;
    }

    private void removeCompletedCursors(final List<MongoCursor<Document>> cursorList,
                                        final List<CanvasMatches> matchesList) {
        MongoCursor<Document> cursor;
        for (int i = matchesList.size() - 1; i >=0; i--) {
            if (matchesList.get(i) == null) {
                matchesList.remove(i);
                cursor = cursorList.remove(i);
                cursor.close();
            }
        }
    }

    private int updateMatches(final List<MongoCursor<Document>> cursorList,
                              final List<CanvasMatches> matchesList,
                              final int index) {
        CanvasMatches canvasMatches = null;
        final MongoCursor<Document> cursor = cursorList.get(index);
        if (cursor.hasNext()) {
            canvasMatches = CanvasMatches.fromJson(cursor.next().toJson());
        }
        matchesList.set(index, canvasMatches);
        return (canvasMatches == null ? 1 : 0);
    }

    private Document getOutsideGroupQuery(final String groupId) {
        final List<Document> queryList = new ArrayList<>();
        queryList.add(new Document("pGroupId", groupId).append(
                "qGroupId", new Document(QueryOperators.NE, groupId)));
        queryList.add(new Document("qGroupId", groupId).append(
                "pGroupId", new Document(QueryOperators.NE, groupId)));
        return new Document(QueryOperators.OR, queryList);
    }

    private Document getInvolvingObjectQuery(final String groupId,final String id){
        final List<Document> queryList = new ArrayList<>();
        queryList.add(new Document("pGroupId", groupId).append(
                "pId", id));
        queryList.add(new Document("qGroupId", groupId).append(
                "qId", id));
        return new Document(QueryOperators.OR, queryList);
    }

    private Document getInvolvingObjectAndGroupQuery(final String groupId,final String id, final String qGroupId){
       final List<Document> queryList = new ArrayList<>();
         queryList.add(new Document("pGroupId", groupId).append(
                 "pId", id).append("qGroupId",qGroupId));
         queryList.add(new Document("qGroupId", groupId).append(
                 "qId", id).append("pGroupId",qGroupId));

        return new Document(QueryOperators.OR, queryList);
    }

    private void ensureMatchIndexes(final MongoCollection<Document> collection) {
        MongoUtil.createIndex(collection,
                              new Document("pGroupId", 1).append(
                                      "qGroupId", 1).append(
                                      "pId", 1).append(
                                      "qId", 1),
                              MATCH_A_OPTIONS);
        MongoUtil.createIndex(collection,
                              new Document("qGroupId", 1),
                              MATCH_B_OPTIONS);
    }

    private static final Logger LOG = LoggerFactory.getLogger(MatchDao.class);

    private static final Document MATCH_ORDER_BY =
            new Document("pGroupId", 1).append("qGroupId", 1).append("pId", 1).append("qId", 1);
    private static final String MATCH_ORDER_BY_JSON = MATCH_ORDER_BY.toJson();
    private static final Document EXCLUDE_MONGO_ID_KEY = new Document("_id", 0);
    private static final String EXCLUDE_MONGO_ID_KEY_JSON = EXCLUDE_MONGO_ID_KEY.toJson();
    private static final byte[] OPEN_BRACKET = "[".getBytes();
    private static final byte[] COMMA_WITH_NEW_LINE = ",\n".getBytes();
    private static final byte[] CLOSE_BRACKET = "]".getBytes();

    private static final IndexOptions MATCH_A_OPTIONS = new IndexOptions().unique(true).background(true).name("A");
    private static final IndexOptions MATCH_B_OPTIONS = new IndexOptions().background(true).name("B");

}
