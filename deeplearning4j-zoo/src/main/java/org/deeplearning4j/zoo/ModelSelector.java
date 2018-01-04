package org.deeplearning4j.zoo;

import org.deeplearning4j.nn.conf.WorkspaceMode;
import org.deeplearning4j.zoo.model.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Helper class for selecting multiple models from the zoo.
 *
 * @author Justin Long (crockpotveggies)
 */
public class ModelSelector {

    public static Map<ZooType, ZooModel> select(ZooType zooType) {
        return select(zooType, 1, 123,  WorkspaceMode.SEPARATE);
    }

    public static Map<ZooType, ZooModel> select(ZooType zooType, int numLabels) {
        return select(zooType, numLabels, 123, WorkspaceMode.SEPARATE);
    }

    public static Map<ZooType, ZooModel> select(ZooType zooType, int numLabels, WorkspaceMode workspaceMode) {
        return select(zooType, numLabels, 123, workspaceMode);
    }

    public static Map<ZooType, ZooModel> select(ZooType zooType, int numLabels, int seed) {
        return select(zooType, numLabels, seed, WorkspaceMode.SEPARATE);
    }

    /**
     * Select multiple models from the zoo according to type.
     *
     * @param zooType
     * @param numLabels
     * @param seed
     * @return A hashmap of zoo types and models.
     */
    public static Map<ZooType, ZooModel> select(ZooType zooType, int numLabels, int seed, WorkspaceMode workspaceMode) {
        return select(new HashMap<ZooType, ZooModel>(), zooType, numLabels, seed, workspaceMode);
    }

    public static Map<ZooType, ZooModel> select(WorkspaceMode workspaceMode, ZooType... zooTypes) {
        return select(0, 123, workspaceMode, zooTypes);
    }

    public static Map<ZooType, ZooModel> select(ZooType... zooTypes) {
        return select(0, 123, WorkspaceMode.SEPARATE, zooTypes);
    }

    /**
     * Select specific models from the zoo.
     *
     * @param numLabels
     * @param seed
     * @param workspaceMode
     * @param zooTypes
     * @return A hashmap of zoo types and models.
     */
    public static Map<ZooType, ZooModel> select(int numLabels, int seed, WorkspaceMode workspaceMode,
                    ZooType... zooTypes) {
        Map<ZooType, ZooModel> netmap = new HashMap<>();

        for (ZooType zooType : zooTypes) {
            select(netmap, zooType, numLabels, seed, workspaceMode);
        }
        return netmap;
    }

    private static Map<ZooType, ZooModel> select(Map<ZooType, ZooModel> netmap, ZooType zooType, int numLabels,
                    int seed, WorkspaceMode workspaceMode) {

        switch (zooType) {
            case ALL:
                netmap.putAll(ModelSelector.select(ZooType.CNN, numLabels, seed, workspaceMode));
                netmap.putAll(ModelSelector.select(ZooType.RNN, numLabels, seed, workspaceMode));
                break;
            // CNN models
            case CNN:
                netmap.putAll(ModelSelector.select(ZooType.SIMPLECNN, numLabels, seed, workspaceMode));
                netmap.putAll(ModelSelector.select(ZooType.ALEXNET, numLabels, seed, workspaceMode));
                netmap.putAll(ModelSelector.select(ZooType.LENET, numLabels, seed, workspaceMode));
                netmap.putAll(ModelSelector.select(ZooType.GOOGLENET, numLabels, seed, workspaceMode));
                netmap.putAll(ModelSelector.select(ZooType.RESNET50, numLabels, seed, workspaceMode));
                netmap.putAll(ModelSelector.select(ZooType.VGG16, numLabels, seed, workspaceMode));
                netmap.putAll(ModelSelector.select(ZooType.VGG19, numLabels, seed, workspaceMode));
                netmap.putAll(ModelSelector.select(ZooType.DARKNET19, numLabels, seed, workspaceMode));
                netmap.putAll(ModelSelector.select(ZooType.TINYYOLO, numLabels, seed, workspaceMode));
                break;
            // RNN models
            case RNN:
                netmap.putAll(ModelSelector.select(ZooType.TEXTGENLSTM, numLabels, seed, workspaceMode));
                break;
            case TEXTGENLSTM:
                netmap.put(ZooType.TEXTGENLSTM, new TextGenerationLSTM(numLabels, seed, workspaceMode));
                break;
            case SIMPLECNN:
                netmap.put(ZooType.SIMPLECNN, new SimpleCNN(numLabels, seed, workspaceMode));
                break;
            case ALEXNET:
                netmap.put(ZooType.ALEXNET, new AlexNet(numLabels, seed, workspaceMode));
                break;
            case LENET:
                netmap.put(ZooType.LENET, new LeNet(numLabels, seed, workspaceMode));
                break;
            case INCEPTIONRESNETV1:
                netmap.put(ZooType.INCEPTIONRESNETV1,
                                new InceptionResNetV1(numLabels, seed, workspaceMode));
                break;
            case FACENETNN4SMALL2:
                netmap.put(ZooType.FACENETNN4SMALL2, new FaceNetNN4Small2(numLabels, seed, workspaceMode));
                break;
            case GOOGLENET:
                netmap.put(ZooType.LENET, new GoogLeNet(numLabels, seed, workspaceMode));
                break;
            case RESNET50:
                netmap.put(ZooType.RESNET50, new ResNet50(numLabels, seed, workspaceMode));
                break;
            case VGG16:
                netmap.put(ZooType.VGG16, new VGG16(numLabels, seed, workspaceMode));
                break;
            case VGG19:
                netmap.put(ZooType.VGG19, new VGG19(numLabels, seed, workspaceMode));
                break;
            case DARKNET19:
                netmap.put(ZooType.DARKNET19, new Darknet19(numLabels, seed, workspaceMode));
                break;
            case TINYYOLO:
                netmap.put(ZooType.TINYYOLO, new TinyYOLO(numLabels, seed, workspaceMode));
                break;
            default:
                // do nothing
        }

        if (netmap.size() == 0)
            throw new IllegalArgumentException("Zero models have been selected for benchmarking.");

        return netmap;
    }
}
