package org.deeplearning4j.zoo;

import lombok.extern.slf4j.Slf4j;
import org.deeplearning4j.nn.conf.WorkspaceMode;
import org.junit.Test;
import org.nd4j.linalg.factory.Nd4j;

import java.util.HashMap;
import java.util.Map;

/**
 * Tests downloads and checksum verification.
 *
 * @note This test first deletes the ~/.deeplearning4j/ cache directory.
 * @author Justin Long (crockpotveggies)
 */
@Slf4j
public class TestDownload {

    @Test
    public void testDownloadAllModels() throws Exception {
        // clean up
        if (ZooModel.ROOT_CACHE_DIR.exists())
            ZooModel.ROOT_CACHE_DIR.delete();

        // iterate through each available model
        //        Map<ZooType, ZooModel> models = ModelSelector.select(ZooType.CNN, 10);
        Map<ZooType, ZooModel> models = new HashMap<>();
        models.putAll(ModelSelector.select(ZooType.LENET, 10, 12345, WorkspaceMode.SINGLE));
        models.putAll(ModelSelector.select(ZooType.SIMPLECNN, 10, 12345, WorkspaceMode.SINGLE));


        for (Map.Entry<ZooType, ZooModel> entry : models.entrySet()) {
            log.info("Testing zoo model " + entry.getKey());
            ZooModel model = entry.getValue();

            for (PretrainedType pretrainedType : PretrainedType.values()) {
                if (model.pretrainedAvailable(pretrainedType)) {
                    model.initPretrained(pretrainedType);
                }
            }

            // clean up for current model
            Nd4j.getWorkspaceManager().destroyAllWorkspacesForCurrentThread();
            System.gc();
            Thread.sleep(1000);
        }
    }

}
