
package edu.ucla.library.bucketeer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.Optional;

import org.junit.Test;

import info.freelibrary.util.StringUtils;

import edu.ucla.library.bucketeer.Job.WorkflowState;
import edu.ucla.library.bucketeer.utils.GenericFilePathPrefix;
import edu.ucla.library.bucketeer.utils.IFilePathPrefix;
import edu.ucla.library.bucketeer.utils.UCLAFilePathPrefix;
import io.vertx.core.json.JsonObject;

/**
 * Tests of the Item class.
 */
public class ItemTest extends AbstractBucketeerTest {

    private static final String TEST_ID = "asdf001";

    private static final String FILE_PATH = "src/test/resources/images/test.tif";

    private static final String IIIF_URL = "http://iiif.test.edu/" + TEST_ID;

    private static final String FILE_PATH_KEY = "filePath";

    /**
     * Tests Item construction with ID and file path.
     */
    @Test
    public final void testItemStringString() throws IOException {
        final Item item = new Item(TEST_ID, FILE_PATH);

        assertEquals(TEST_ID, item.getID());
        assertEquals(new File(FILE_PATH).getPath(), item.getFilePath().get());
    }

    /**
     * Tests getting and setting file path prefix.
     */
    @Test
    public final void testSetFilePathPrefix() {
        final Item item = new Item(TEST_ID, FILE_PATH).setFilePathPrefix(new GenericFilePathPrefix());

        assertEquals("GenericFilePathPrefix", item.getFilePathPrefix().getClass().getSimpleName());
    }

    /**
     * Tests setting and getting workflow state.
     */
    @Test
    public final void testSetWorkflowState() {
        final Item item = new Item(TEST_ID, FILE_PATH).setWorkflowState(WorkflowState.FAILED);

        assertEquals(WorkflowState.FAILED, item.getWorkflowState());
    }

    /**
     * Tests whether <code>getFile</code> works.
     */
    @Test
    public final void testGetFile() {
        assertTrue(new Item(TEST_ID, FILE_PATH).getFile().get().exists());
    }

    /**
     * Tests getting the file path.
     *
     * @throws IOException If there is trouble getting the canonical file path
     */
    @Test
    public final void testGetFilePath() throws IOException {
        final String path = new Item(TEST_ID, FILE_PATH).getFile().get().getAbsolutePath();

        assertEquals(new File(FILE_PATH).getAbsolutePath(), path);
    }

    /**
     * Tests setting the file path.
     *
     * @throws IOException If there is trouble getting the canonical file path
     */
    @Test
    public final void testSetFilePath() throws IOException {
        final Item item = new Item(TEST_ID, "fake_path");
        final String filePath = new File(FILE_PATH).getAbsolutePath();

        assertEquals(filePath, item.setFilePath(Optional.of(filePath)).getFilePath().get());
    }

    /**
     * Tests setting an empty file path.
     *
     */
    @Test
    public final void testSetEmptyFilePath() {
        final Item item = new Item(TEST_ID, null);

        assertEquals(Optional.empty(), item.getFilePath());
    }

    /**
     * Tests setting an empty file path via default constructor.
     *
     */
    @Test
    public final void testSetEmptyFilePathDefaultConstructor() {
        final Item item = new Item();

        assertEquals(Optional.empty(), item.getFilePath());
    }

    /**
     * Tests getting the item ID.
     */
    @Test
    public final void testGetID() {
        assertEquals(TEST_ID, new Item(TEST_ID, FILE_PATH).getID());
    }

    /**
     * Tests setting the item ID.
     */
    @Test
    public final void testSetID() {
        assertEquals(TEST_ID, new Item("fake_id", FILE_PATH).setID(TEST_ID).getID());
    }

    /**
     * Tests getting and setting the access URL.
     */
    @Test
    public final void testSetAccessURL() {
        assertEquals(IIIF_URL, new Item(TEST_ID, FILE_PATH).setAccessURL(IIIF_URL).getAccessURL());
    }

    /**
     * Tests whether we can successfully test the item's set file.
     */
    @Test
    public final void testHasFile() {
        assertTrue(new Item(TEST_ID, FILE_PATH).hasFile());
    }

    /**
     * Tests the <code>toJSON</code> method.
     */
    @Test
    public final void testToJSON() throws IOException {
        final Item item = new Item(TEST_ID, FILE_PATH).setWorkflowState(WorkflowState.FAILED);
        final JsonObject expected = new JsonObject(StringUtils.read(new File("src/test/resources/json/item.json")));

        item.setFilePathPrefix(new GenericFilePathPrefix());
        expected.put(FILE_PATH_KEY, new File(FILE_PATH).getPath());

        assertEquals(expected, item.toJSON());
    }

    /**
     * Tests the <code>toJSON</code> method.
     */
    @Test
    public final void testToJSONWithGenericPrefix() throws IOException {
        final Item item = new Item(TEST_ID, FILE_PATH).setWorkflowState(WorkflowState.EMPTY);
        final JsonObject expected = new JsonObject(StringUtils.read(new File(
                "src/test/resources/json/generic-item.json")));

        expected.put(FILE_PATH_KEY, new File(FILE_PATH).getPath());

        assertEquals(expected, item.setFilePathPrefix(new GenericFilePathPrefix()).toJSON());
    }

    /**
     * Tests the <code>toJSON</code> method.
     */
    @Test
    public final void testToJSONWithUclaPrefix() throws IOException {
        final Item item = new Item(TEST_ID, FILE_PATH).setWorkflowState(WorkflowState.EMPTY);
        final String expected = StringUtils.read(new File("src/test/resources/json/ucla-item.json"));
        final IFilePathPrefix prefix = new UCLAFilePathPrefix("/tmp");

        assertEquals(new JsonObject(expected), item.setFilePathPrefix(prefix).toJSON());
    }
}
