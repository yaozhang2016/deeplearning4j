package org.deeplearning4j.eval.serde;

import org.deeplearning4j.eval.ConfusionMatrix;
import org.nd4j.shade.jackson.core.JsonParser;
import org.nd4j.shade.jackson.core.JsonProcessingException;
import org.nd4j.shade.jackson.databind.DeserializationContext;
import org.nd4j.shade.jackson.databind.JsonDeserializer;
import org.nd4j.shade.jackson.databind.JsonNode;
import org.nd4j.shade.jackson.databind.node.ArrayNode;
import org.nd4j.shade.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * A JSON deserializer for {@code ConfusionMatrix<Integer>} instances, used in {@link org.deeplearning4j.eval.Evaluation}
 *
 * @author Alex Black
 */
public class ConfusionMatrixDeserializer extends JsonDeserializer<ConfusionMatrix<Integer>> {
    @Override
    public ConfusionMatrix<Integer> deserialize(JsonParser jp, DeserializationContext ctxt)
                    throws IOException, JsonProcessingException {
        JsonNode n = jp.getCodec().readTree(jp);

        //Get class names/labels
        ArrayNode classesNode = (ArrayNode) n.get("classes");
        List<Integer> classes = new ArrayList<>();
        for (JsonNode cn : classesNode) {
            classes.add(cn.asInt());
        }

        ConfusionMatrix<Integer> cm = new ConfusionMatrix<>(classes);

        ObjectNode matrix = (ObjectNode) n.get("matrix");
        Iterator<Map.Entry<String, JsonNode>> matrixIter = matrix.fields();
        while (matrixIter.hasNext()) {
            Map.Entry<String, JsonNode> e = matrixIter.next();

            int actualClass = Integer.parseInt(e.getKey());
            ArrayNode an = (ArrayNode) e.getValue();

            ArrayNode innerMultiSetKey = (ArrayNode) an.get(0);
            ArrayNode innerMultiSetCount = (ArrayNode) an.get(1);

            Iterator<JsonNode> iterKey = innerMultiSetKey.iterator();
            Iterator<JsonNode> iterCnt = innerMultiSetCount.iterator();
            while (iterKey.hasNext()) {
                int predictedClass = iterKey.next().asInt();
                int count = iterCnt.next().asInt();

                cm.add(actualClass, predictedClass, count);
            }
        }

        return cm;
    }
}
