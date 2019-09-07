
package edu.ucla.library.bucketeer.handlers;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import info.freelibrary.util.Logger;
import info.freelibrary.util.LoggerFactory;

import edu.ucla.library.bucketeer.Config;
import edu.ucla.library.bucketeer.Constants;
import edu.ucla.library.bucketeer.HTTP;
import edu.ucla.library.bucketeer.MessageCodes;
import edu.ucla.library.bucketeer.Metadata;
import edu.ucla.library.bucketeer.Metadata.WorkflowState;
import edu.ucla.library.bucketeer.Op;
import edu.ucla.library.bucketeer.verticles.SlackMessageVerticle;
import edu.ucla.library.bucketeer.verticles.ThumbnailVerticle;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.shareddata.AsyncMap;
import io.vertx.core.shareddata.Counter;
import io.vertx.core.shareddata.SharedData;
import io.vertx.ext.web.RoutingContext;

public class BatchJobStatusHandler extends AbstractBucketeerHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(BatchJobStatusHandler.class, Constants.MESSAGES);

    private final JsonObject myConfig;

    private Vertx myVertx;

    /**
     * Creates a handler to ingest CSV files.
     *
     * @param aConfig An application configuration
     */
    public BatchJobStatusHandler(final JsonObject aConfig) throws IOException {
        myConfig = aConfig;
    }

    @Override
    public void handle(final RoutingContext aContext) {
        final HttpServerResponse response = aContext.response();
        final HttpServerRequest request = aContext.request();
        final Vertx vertx = aContext.vertx();
        final SharedData sharedData = vertx.sharedData();

        if (myVertx == null) {
            myVertx = vertx;
        }

        // imageId get decoded, but we actually want the encoded version of it to compare with the jobs queue
        final String imageId = URLEncoder.encode(request.getParam(Constants.IMAGE_ID), StandardCharsets.UTF_8);
        final String jobName = request.getParam(Constants.JOB_NAME);
        final boolean success = Boolean.parseBoolean(request.getParam(Op.SUCCESS));

        sharedData.<String, List<Metadata>>getLocalAsyncMap(Constants.LAMBDA_MAP, getMap -> {
            if (getMap.succeeded()) {
                final AsyncMap<String, List<Metadata>> map = getMap.result();

                map.keys(keyCheck -> {
                    if (keyCheck.succeeded()) {
                        final Set<String> jobs = keyCheck.result();

                        if (jobs.contains(jobName)) {
                            map.get(jobName, getJob -> {
                                if (getJob.succeeded()) {
                                    final List<Metadata> metadataList = getJob.result();
                                    final ListIterator<Metadata> iterator = metadataList.listIterator();

                                    boolean finished = true;
                                    boolean found = false;

                                    while (iterator.hasNext()) {
                                        final Metadata metadata = iterator.next();
                                        final String id = metadata.getID();

                                        if (imageId.equals(id)) {
                                            if (!success) {
                                                metadata.setWorkflowState(WorkflowState.FAILED);
                                            } else {
                                                metadata.setWorkflowState(WorkflowState.SUCCEEDED);
                                            }

                                            found = true;
                                        }

                                        if (WorkflowState.EMPTY.equals(metadata.getWorkflowState())) {
                                            finished = false;
                                        }
                                    }

                                    // Could be some random submission, but it's worth flagging as suspicious
                                    if (!found) {
                                        LOGGER.warn(MessageCodes.BUCKETEER_077, jobName, imageId);
                                    }

                                    // Have we finished processing all the images in this job?
                                    if (finished) {
                                        decrementJobsCounter(sharedData, map, jobName, response, true);
                                    } else {
                                        decrementJobsCounter(sharedData, map, jobName, response, false);
                                    }
                                } else {
                                    returnError(getJob.cause(), MessageCodes.BUCKETEER_076, jobName, response);
                                }
                            });
                        } else {
                            returnError(MessageCodes.BUCKETEER_075, jobName, response);
                        }
                    } else {
                        returnError(keyCheck.cause(), MessageCodes.BUCKETEER_062, jobName, response);
                    }
                });
            } else {
                returnError(getMap.cause(), MessageCodes.BUCKETEER_063, jobName, response);
            }
        });
    }

    /**
     * Return the Logger associated with this handler.
     *
     * @return The Logger associated with this handler
     */
    @Override
    protected Logger getLogger() {
        return LOGGER;
    }

    /**
     * Return an error message to the browser.
     *
     * @param aMessageCode A MessageCode string
     * @param aDetail Details about the exception
     * @param aResponse An HTTP server response
     */
    private void returnError(final String aMessageCode, final String aDetail, final HttpServerResponse aResponse) {
        returnError(null, aMessageCode, aDetail, aResponse);
    }

    /**
     * Return an error message to the browser.
     *
     * @param aThrowable An exception with a stack trace
     * @param aMessageCode A MessageCode string
     * @param aDetail Details about the exception
     * @param aResponse An HTTP server response
     */
    private void returnError(final Throwable aThrowable, final String aMessageCode, final String aDetail,
            final HttpServerResponse aResponse) {
        final String errorChannel = myConfig.getString(Config.SLACK_ERROR_CHANNEL_ID);
        final String errorMessage = LOGGER.getMessage(aMessageCode, aDetail);

        if (aThrowable != null) {
            LOGGER.error(aThrowable, errorMessage);
        } else {
            LOGGER.error(errorMessage);
        }

        // Send notice about error to Slack channel too
        sendSlackMessage(LOGGER.getMessage(MessageCodes.BUCKETEER_110, errorMessage), errorChannel);

        aResponse.setStatusCode(HTTP.INTERNAL_SERVER_ERROR);
        aResponse.setStatusMessage(errorMessage);
        aResponse.end();
    }

    /**
     * Send a message to the specified Slack channel.
     *
     * @param aMessageText Text of the message we want to send
     * @param aChannelId ID of the channel to which we want to send this message
     */
    private void sendSlackMessage(final String aMessageText, final String aChannelId) {
        sendSlackMessage(aMessageText, aChannelId, null);
    }

    /**
     * Send a message to the specified Slack channel with a list of metadata records.
     *
     * @param aMessageText Text of the message we want to send
     * @param aChannelId ID of the channel to which we want to send this message
     */
    private void sendSlackMessage(final String aMessageText, final String aChannelId,
            final List<Metadata> aMetadataList) {
        final EventBus eventBus = myVertx.eventBus();
        final JsonObject message = new JsonObject();

        // Build our message object
        message.put(Constants.SLACK_MESSAGE_TEXT, aMessageText);
        message.put(Config.SLACK_CHANNEL_ID, aChannelId);

        try {
            final JsonArray metadataJson = new JsonArray(new ObjectMapper().writeValueAsString(aMetadataList));

            message.put(Constants.BATCH_METADATA, metadataJson);
            eventBus.send(SlackMessageVerticle.class.getName(), message);
        } catch (final JsonProcessingException details) {
            final String errorChannel = myConfig.getString(Config.SLACK_ERROR_CHANNEL_ID);
            final String errorMessage = details.getMessage();

            LOGGER.error(details, errorMessage);
            sendSlackMessage(LOGGER.getMessage(MessageCodes.BUCKETEER_110, errorMessage), errorChannel);
        }
    }

    /**
     * Decrement the job counter and do a final check if it's the last job in a batch run.
     *
     * @param aSharedData Data shared across different handler instances
     * @param aJobName The name of the batch job that's currently running
     * @param aResponse An HTTP response
     * @param aCompletedRun Whether or not the batch job has completed
     */
    private void decrementJobsCounter(final SharedData aSharedData, final AsyncMap<String, List<Metadata>> aJobsMap,
            final String aJobName, final HttpServerResponse aResponse, final boolean aCompletedRun) {
        final String slackErrorChannelID = myConfig.getString(Config.SLACK_ERROR_CHANNEL_ID);

        aSharedData.getCounter(aJobName, getCounter -> {
            if (getCounter.succeeded()) {
                final Counter counter = getCounter.result();

                counter.decrementAndGet(decrement -> {
                    if (decrement.succeeded()) {
                        // Double check our belief that this is the last job in the batch run
                        if (aCompletedRun && !decrement.result().equals(0L)) {
                            final String errorMessage = LOGGER.getMessage(MessageCodes.BUCKETEER_079);
                            final String slackMessage = LOGGER.getMessage(MessageCodes.BUCKETEER_110, errorMessage);

                            LOGGER.error(errorMessage);

                            // And post to Slack about it so we can investigate
                            sendSlackMessage(slackMessage, slackErrorChannelID);
                        } else if (aCompletedRun) {
                            LOGGER.info(MessageCodes.BUCKETEER_081, aJobName);

                            // Remove the batch from our jobs queue
                            aJobsMap.remove(aJobName, removeJob -> {
                                if (removeJob.succeeded()) {
                                    finalizeJob(removeJob.result());
                                    returnSuccess(aResponse);
                                } else {
                                    returnError(removeJob.cause(), MessageCodes.BUCKETEER_082, aJobName, aResponse);
                                }
                            });
                        } else {
                            returnSuccess(aResponse);
                        }
                    } else {
                        returnError(decrement.cause(), MessageCodes.BUCKETEER_080, aJobName, aResponse);
                    }
                });
            } else {
                returnError(getCounter.cause(), MessageCodes.BUCKETEER_066, aJobName, aResponse);
            }
        });
    }

    /**
     * Wrap up notification services once the batch job has completed.
     *
     * @param aMetadataList A list of metadata objects
     */
    private void finalizeJob(final List<Metadata> aMetadataList) {
        final String slackChannelID = myConfig.getString(Config.SLACK_CHANNEL_ID);
        final String slackHandle = aMetadataList.get(0).getSlackHandle();
        final JsonArray thumbnails = new JsonArray();

        // Go through metadata and look for ones that have just succeeded being processed
        for (final Metadata metadata : aMetadataList) {
            if (WorkflowState.SUCCEEDED.equals(metadata.getWorkflowState())) {
                thumbnails.add(metadata.getID());
            }
        }

        // If there are recently succeeded images, send them off for thumbnail generation
        if (thumbnails.size() > 0) {
            final JsonObject thumbnailMessage = new JsonObject();

            thumbnailMessage.put(Constants.IMAGE_ID_ARRAY, thumbnails);
            sendMessage(myVertx, thumbnailMessage, ThumbnailVerticle.class.getName());
        }

        // Once that's completed, we can let the user know their job is ready
        sendSlackMessage(LOGGER.getMessage(MessageCodes.BUCKETEER_111, slackHandle), slackChannelID, aMetadataList);
    }

    /**
     * Return a successful response to the browser.
     *
     * @param aResponse A HTTP server response
     */
    private void returnSuccess(final HttpServerResponse aResponse) {
        aResponse.setStatusCode(HTTP.NO_CONTENT);
        aResponse.end();
    }

}
