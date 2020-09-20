
package edu.ucla.library.bucketeer.verticles;

import static edu.ucla.library.bucketeer.Constants.MESSAGES;

import java.io.File;
import java.io.IOException;

import info.freelibrary.util.Logger;
import info.freelibrary.util.LoggerFactory;

import edu.ucla.library.bucketeer.Config;
import edu.ucla.library.bucketeer.Constants;
import edu.ucla.library.bucketeer.HTTP;
import edu.ucla.library.bucketeer.MessageCodes;
import edu.ucla.library.bucketeer.Op;
import edu.ucla.library.bucketeer.converters.Conversion;
import edu.ucla.library.bucketeer.converters.Converter;
import edu.ucla.library.bucketeer.converters.ConverterFactory;
import edu.ucla.library.bucketeer.converters.KakaduConverter;
import edu.ucla.library.bucketeer.utils.CodeUtils;

import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.eventbus.ReplyException;
import io.vertx.core.json.JsonObject;

public class ImageWorkerVerticle extends AbstractBucketeerVerticle {

    private static final Logger LOGGER = LoggerFactory.getLogger(ImageWorkerVerticle.class, MESSAGES);

    /* Default delay for requeuing, measured in seconds */
    private static final long DEFAULT_REQUEUE_DELAY = 1;

    private long myRequeueDelay;

    @Override
    public void start() throws IOException {
        myRequeueDelay = config().getLong(Config.S3_REQUEUE_DELAY, DEFAULT_REQUEUE_DELAY) * 1000;

        if (LOGGER.isDebugEnabled()) {
            final String className = ImageWorkerVerticle.class.getSimpleName();
            final String threadName = Thread.currentThread().getName();

            LOGGER.debug(MessageCodes.BUCKETEER_004, className, threadName);
        }

        getJsonConsumer().handler(aMessage -> {
            final JsonObject json = aMessage.body();
            final File tiffFile = new File(json.getString(Constants.FILE_PATH));
            final String imageID = json.getString(Constants.IMAGE_ID);
            final Converter converter = ConverterFactory.getConverter(KakaduConverter.class);

            LOGGER.debug(MessageCodes.BUCKETEER_024, imageID, json.getString(Constants.FILE_PATH));

            try {
                final File jpx = converter.convert(imageID, tiffFile, Conversion.LOSSLESS);
                final JsonObject message = new JsonObject();
                final Promise<Void> promise = Promise.promise();

                message.put(Constants.FILE_PATH, jpx.getAbsolutePath());
                message.put(Constants.IMAGE_ID, jpx.getName());

                // Handle the S3 storage request's response
                promise.future().onComplete(upload -> {
                    if (upload.succeeded()) {
                        aMessage.reply(Op.SUCCESS);
                    } else {
                        final Throwable error = upload.cause();
                        final String errorMessage = error.getMessage();

                        LOGGER.error(error, errorMessage);
                        aMessage.fail(HTTP.INTERNAL_SERVER_ERROR, errorMessage);
                    }
                });

                // Request our JP2 be uploaded to S3
                sendImageRequest(S3BucketVerticle.class.getName(), message, promise);
            } catch (final InterruptedException | IOException details) {
                LOGGER.error(details, details.getMessage());
                aMessage.fail(HTTP.INTERNAL_SERVER_ERROR, details.getMessage());
            }
        });
    }

    @Override
    protected Logger getLogger() {
        return LOGGER;
    }

    /**
     * This message sender is specifically for item processing requests; it behaves differently from the default message
     * sender that our other handlers use.
     *
     * @param aJsonObject A message with information about the image to upload
     * @param aListener The destination of the message
     * @param aPromise A promise that the JP2/JPX image has been uploaded
     */
    private void sendImageRequest(final String aListener, final JsonObject aJsonObject, final Promise<Void> aPromise) {
        final DeliveryOptions options = new DeliveryOptions().setSendTimeout(Integer.MAX_VALUE);
        final Vertx vertx = getVertx();

        vertx.eventBus().request(aListener, aJsonObject, options, response -> {
            if (response.failed()) {
                final Throwable exception = response.cause();

                if (exception != null) {
                    if (exception instanceof ReplyException) {
                        final ReplyException replyException = (ReplyException) exception;
                        final String messageCode = CodeUtils.getCode(replyException.failureCode());
                        final String exceptionMessage = LOGGER.getMessage(messageCode, replyException.getMessage());

                        LOGGER.error(replyException, MessageCodes.BUCKETEER_005, aListener, exceptionMessage);
                    } else {
                        LOGGER.error(exception, MessageCodes.BUCKETEER_005, aListener, exception.getMessage());
                    }
                } else {
                    LOGGER.error(MessageCodes.BUCKETEER_005, aListener, MessageCodes.BUCKETEER_136);
                }

                aPromise.fail(exception);
            } else if (response.result().body().equals(Op.RETRY)) {
                vertx.setTimer(myRequeueDelay, timer -> {
                    sendImageRequest(aListener, aJsonObject, aPromise);
                });
            } else {
                aPromise.complete();
            }
        });
    }
}
