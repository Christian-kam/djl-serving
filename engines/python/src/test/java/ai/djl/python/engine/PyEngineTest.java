/*
 * Copyright 2021 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"). You may not use this file except in compliance
 * with the License. A copy of the License is located at
 *
 * http://aws.amazon.com/apache2.0/
 *
 * or in the "license" file accompanying this file. This file is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
 * OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */
package ai.djl.python.engine;

import ai.djl.ModelException;
import ai.djl.engine.Engine;
import ai.djl.engine.EngineException;
import ai.djl.inference.Predictor;
import ai.djl.inference.streaming.ChunkedBytesSupplier;
import ai.djl.modality.Input;
import ai.djl.modality.Output;
import ai.djl.ndarray.BytesSupplier;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.Shape;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.training.util.DownloadUtils;
import ai.djl.translate.NoopTranslator;
import ai.djl.translate.TranslateException;
import ai.djl.util.JsonUtils;
import ai.djl.util.RandomUtils;

import com.google.gson.JsonElement;
import com.google.gson.reflect.TypeToken;

import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public class PyEngineTest {

    @BeforeClass
    public void setUp() {
        System.setProperty("ENGINE_CACHE_DIR", "build/cache");
    }

    @AfterClass
    public void tierDown() {
        System.clearProperty("ENGINE_CACHE_DIR");
    }

    @Test
    public void testPyEngine() {
        Engine engine = Engine.getInstance();
        Assert.assertNotNull(engine.getVersion());
        Assert.assertTrue(engine.toString().startsWith("Python:"));
        Assert.assertThrows(UnsupportedOperationException.class, engine::newGradientCollector);
        Assert.assertThrows(UnsupportedOperationException.class, () -> engine.newSymbolBlock(null));
    }

    @Test
    public void testNDArray() {
        try (NDManager manager = NDManager.newBaseManager()) {
            NDArray zeros = manager.zeros(new Shape(1, 2));
            float[] data = zeros.toFloatArray();
            Assert.assertEquals(data[0], 0);

            NDArray ones = manager.ones(new Shape(1, 2));
            data = ones.toFloatArray();
            Assert.assertEquals(data[0], 1);

            float[] buf = {0f, 1f, 2f, 3f};
            NDArray array = manager.create(buf);
            Assert.assertEquals(array.toFloatArray(), buf);
        }
    }

    @Test
    public void testModelLoadingTimeout() throws IOException, ModelException {
        Criteria<NDList, NDList> criteria =
                Criteria.builder()
                        .setTypes(NDList.class, NDList.class)
                        .optModelPath(Paths.get("src/test/resources/accumulate"))
                        .optTranslator(new NoopTranslator())
                        .optOption("model_loading_timeout", "1")
                        .optEngine("Python")
                        .build();

        try (ZooModel<NDList, NDList> model = criteria.loadModel()) {
            Assert.assertThrows(EngineException.class, model::newPredictor);
        }
    }

    @Test
    public void testPredictTimeout() throws IOException, ModelException {
        Criteria<NDList, NDList> criteria =
                Criteria.builder()
                        .setTypes(NDList.class, NDList.class)
                        .optModelPath(Paths.get("src/test/resources/accumulate"))
                        .optTranslator(new NoopTranslator())
                        .optOption("predict_timeout", "1")
                        .optEngine("Python")
                        .build();

        try (ZooModel<NDList, NDList> model = criteria.loadModel();
                Predictor<NDList, NDList> predictor = model.newPredictor()) {
            NDArray x = model.getNDManager().create(new float[] {1});
            Assert.assertThrows(TranslateException.class, () -> predictor.predict(new NDList(x)));
        }
    }

    @Test
    public void testPyModel() throws TranslateException, IOException, ModelException {
        Criteria<NDList, NDList> criteria =
                Criteria.builder()
                        .setTypes(NDList.class, NDList.class)
                        .optModelPath(Paths.get("src/test/resources/accumulate"))
                        .optTranslator(new NoopTranslator())
                        .optOption("env", "TEST_ENV1=a,TEST_ENV2=b")
                        .optEngine("Python")
                        .build();
        try (ZooModel<NDList, NDList> model = criteria.loadModel();
                Predictor<NDList, NDList> predictor = model.newPredictor()) {
            NDArray x = model.getNDManager().create(new float[] {1});
            predictor.predict(new NDList(x));
            NDList ret = predictor.predict(new NDList(x));
            float[] expected = {2};
            float[] actual = ret.head().toFloatArray();
            Assert.assertEquals(actual, expected);
        }
    }

    @Test
    public void testEchoModel() throws TranslateException, IOException, ModelException {
        // Echo model doesn't support initialize
        Criteria<NDList, NDList> criteria =
                Criteria.builder()
                        .setTypes(NDList.class, NDList.class)
                        .optModelPath(Paths.get("src/test/resources/echo"))
                        .optTranslator(new NoopTranslator())
                        .optEngine("Python")
                        .build();
        try (ZooModel<NDList, NDList> model = criteria.loadModel();
                Predictor<NDList, NDList> predictor = model.newPredictor()) {
            NDArray x = model.getNDManager().create(new float[] {1});
            NDList ret = predictor.predict(new NDList(x));
            float[] expected = {1};
            float[] actual = ret.head().toFloatArray();
            Assert.assertEquals(actual, expected);
        }
    }

    @Test
    public void testBatchEcho() throws TranslateException, IOException, ModelException {
        Criteria<Input, Output> criteria =
                Criteria.builder()
                        .setTypes(Input.class, Output.class)
                        .optModelPath(Paths.get("src/test/resources/echo"))
                        .optEngine("Python")
                        .build();
        try (ZooModel<Input, Output> model = criteria.loadModel();
                Predictor<Input, Output> predictor = model.newPredictor()) {
            Input in1 = new Input();
            in1.add("test1");
            Input in2 = new Input();
            in2.add("test2");
            List<Input> batch = Arrays.asList(in1, in2);
            List<Output> out = predictor.batchPredict(batch);
            Assert.assertEquals(out.size(), 2);
            Assert.assertEquals(out.get(1).getAsString(0), "test2");
        }
    }

    @Test
    public void testStreamEcho()
            throws TranslateException,
                    IOException,
                    ModelException,
                    InterruptedException,
                    ExecutionException {
        Criteria<Input, Output> criteria =
                Criteria.builder()
                        .setTypes(Input.class, Output.class)
                        .optModelPath(Paths.get("src/test/resources/echo"))
                        .optEngine("Python")
                        .build();
        try (ZooModel<Input, Output> model = criteria.loadModel();
                Predictor<Input, Output> predictor = model.newPredictor()) {
            Input input = new Input();
            input.add("stream", "true");
            Output out = predictor.predict(input);
            BytesSupplier supplier = out.getData();
            Assert.assertTrue(supplier instanceof ChunkedBytesSupplier);
            ChunkedBytesSupplier pub = (ChunkedBytesSupplier) supplier;
            byte[] buf = pub.getAsBytes();
            Assert.assertEquals(buf.length, 20);
        }
    }

    @Test
    public void testAdaptEchoModel() throws TranslateException, IOException, ModelException {
        // Echo model doesn't support initialize
        Criteria<Input, Output> criteria =
                Criteria.builder()
                        .setTypes(Input.class, Output.class)
                        .optModelPath(Paths.get("src/test/resources/adaptecho"))
                        .optEngine("Python")
                        .build();
        try (ZooModel<Input, Output> model = criteria.loadModel();
                Predictor<Input, Output> predictor = model.newPredictor()) {
            Input input = new Input();
            input.add("input");
            input.add("adapter", "ad");
            Output output = predictor.predict(input);
            Assert.assertEquals(output.getData().getAsString(), "dynadinput");
        }
    }

    @Test
    public void testBatchAdaptEchoModel() throws TranslateException, IOException, ModelException {
        // Echo model doesn't support initialize
        Criteria<Input, Output> criteria =
                Criteria.builder()
                        .setTypes(Input.class, Output.class)
                        .optModelPath(Paths.get("src/test/resources/adaptecho"))
                        .optEngine("Python")
                        .build();
        try (ZooModel<Input, Output> model = criteria.loadModel();
                Predictor<Input, Output> predictor = model.newPredictor()) {
            Input in0 = new Input();
            in0.add("input");
            in0.add("adapter", "ad0");
            Input in1 = new Input();
            in1.add("input");
            in1.add("adapter", "ad1");
            List<Output> outputs = predictor.batchPredict(Arrays.asList(in0, in1));
            Assert.assertEquals(outputs.get(0).getData().getAsString(), "dynad0input");
            Assert.assertEquals(outputs.get(1).getData().getAsString(), "dynad1input");
        }
    }

    @Test(expectedExceptions = EngineException.class, expectedExceptionsMessageRegExp = "OOM")
    public void testLoadModelOom()
            throws TranslateException, IOException, ModelException, InterruptedException {
        Criteria<Input, Output> criteria =
                Criteria.builder()
                        .setTypes(Input.class, Output.class)
                        .optModelPath(Paths.get("src/test/resources/oom"))
                        .optEngine("Python")
                        .build();
        ZooModel<Input, Output> model = criteria.loadModel();
        model.newPredictor();
    }

    @Test
    public void testInvokeModelOom()
            throws TranslateException, IOException, ModelException, InterruptedException {
        Criteria<Input, Output> criteria =
                Criteria.builder()
                        .setTypes(Input.class, Output.class)
                        .optModelPath(Paths.get("src/test/resources/echo"))
                        .optEngine("Python")
                        .build();
        try (ZooModel<Input, Output> model = criteria.loadModel();
                Predictor<Input, Output> predictor = model.newPredictor()) {
            Input input = new Input();
            input.add("OOM", "true");
            Output out = predictor.predict(input);
            Assert.assertEquals(out.getCode(), 507);
        }
    }

    @Test
    public void testResnet18() throws TranslateException, IOException, ModelException {
        if (!Boolean.getBoolean("nightly")) {
            return;
        }
        Criteria<Input, Output> criteria =
                Criteria.builder()
                        .setTypes(Input.class, Output.class)
                        .optModelPath(Paths.get("src/test/resources/resnet18"))
                        .optEngine("Python")
                        .build();
        try (ZooModel<Input, Output> model = criteria.loadModel();
                Predictor<Input, Output> predictor = model.newPredictor()) {
            Input input = new Input();
            Path file = Paths.get("build/test/kitten.jpg");
            DownloadUtils.download(
                    new URL("https://resources.djl.ai/images/kitten.jpg"), file, null);
            input.add("data", Files.readAllBytes(file));
            input.addProperty("Content-Type", "image/jpeg");
            Output output = predictor.predict(input);
            String classification = output.getData().getAsString();
            Type type = new TypeToken<Map<String, Double>>() {}.getType();
            Map<String, Double> map = JsonUtils.GSON.fromJson(classification, type);
            Assert.assertTrue(map.containsKey("tabby"));

            // Test batch predict
            List<Input> batch = Arrays.asList(input, input);
            List<Output> ret = predictor.batchPredict(batch);
            Assert.assertEquals(ret.size(), 2);

            // Test npz input
            NDArray ones = model.getNDManager().ones(new Shape(1, 3, 224, 224));
            NDList list = new NDList();
            list.add(ones);
            byte[] buf = list.encode(NDList.Encoding.NPZ);

            input = new Input();
            input.add("data", buf);
            input.addProperty("Content-Type", "tensor/npz");
            output = predictor.predict(input);
            String contentType = output.getProperty("Content-Type", "");
            Assert.assertEquals(contentType, "tensor/npz");
            NDList nd = output.getDataAsNDList(model.getNDManager());
            Assert.assertEquals(nd.get(0).toArray().length, 1000);
        }
    }

    @Test
    public void testResnet18BinaryMode() throws TranslateException, IOException, ModelException {
        if (!Boolean.getBoolean("nightly")) {
            return;
        }
        Criteria<NDList, NDList> criteria =
                Criteria.builder()
                        .setTypes(NDList.class, NDList.class)
                        .optModelPath(Paths.get("src/test/resources/resnet18"))
                        .optEngine("Python")
                        .build();
        try (ZooModel<NDList, NDList> model = criteria.loadModel();
                Predictor<NDList, NDList> predictor = model.newPredictor()) {
            NDArray x = model.getNDManager().ones(new Shape(1, 3, 224, 224));
            NDList ret = predictor.predict(new NDList(x));
            Assert.assertEquals(ret.get(0).getShape().get(1), 1000);
        }
    }

    @Test
    public void testResnet18HybridMode() throws TranslateException, IOException, ModelException {
        if (!Boolean.getBoolean("nightly")) {
            return;
        }
        Criteria<Input, Output> criteria1 =
                Criteria.builder()
                        .setTypes(Input.class, Output.class)
                        .optModelPath(Paths.get("src/test/resources/resnet18"))
                        .optEngine("Python")
                        .optOption("entryPoint", "processing.py")
                        .build();
        Criteria<NDList, NDList> criteria2 =
                Criteria.builder()
                        .setTypes(NDList.class, NDList.class)
                        .optModelPath(Paths.get("src/test/resources/resnet18"))
                        .optEngine("Python")
                        .build();
        try (ZooModel<Input, Output> processingModel = criteria1.loadModel();
                Predictor<Input, Output> processingPredictor = processingModel.newPredictor();
                ZooModel<NDList, NDList> resnet18 = criteria2.loadModel();
                Predictor<NDList, NDList> predictor = resnet18.newPredictor();
                NDManager manager = NDManager.newBaseManager()) {

            Input preProcessing = new Input();
            Path file = Paths.get("build/test/kitten.jpg");
            DownloadUtils.download(
                    new URL("https://resources.djl.ai/images/kitten.jpg"), file, null);
            preProcessing.add("data", Files.readAllBytes(file));
            preProcessing.addProperty("Content-Type", "image/jpeg");
            // calling preprocess() function in processing.py
            preProcessing.addProperty("handler", "preprocess");
            Output preprocessed = processingPredictor.predict(preProcessing);
            NDList list = preprocessed.getDataAsNDList(manager);
            NDArray array = list.singletonOrThrow();
            Assert.assertEquals(array.getShape(), new Shape(1, 3, 224, 224));

            list = predictor.predict(list);
            Assert.assertEquals(list.get(0).getShape(), new Shape(1, 1000));

            Input postProcessing = new Input();
            postProcessing.add("data", list.encode());
            // calling postprocess() function in processing.py
            postProcessing.addProperty("handler", "postprocess");
            Output ret = processingPredictor.predict(postProcessing);
            String json = ret.getData().getAsString();
            Type type = new TypeToken<Map<String, Double>>() {}.getType();
            Map<String, Double> map = JsonUtils.GSON.fromJson(json, type);
            Assert.assertTrue(map.containsKey("tabby"));
        }
    }

    @Test
    public void testHuggingfaceModel() throws TranslateException, IOException, ModelException {
        if (!Boolean.getBoolean("nightly")) {
            return;
        }
        Criteria<Input, Output> criteria =
                Criteria.builder()
                        .setTypes(Input.class, Output.class)
                        .optEngine("Python")
                        .optModelPath(Paths.get("src/test/resources/huggingface"))
                        .optOption(
                                "entryPoint",
                                "https://raw.githubusercontent.com/deepjavalibrary/djl-serving/master/engines/python/setup/djl_python/huggingface.py")
                        .build();
        try (ZooModel<Input, Output> model = criteria.loadModel();
                Predictor<Input, Output> predictor = model.newPredictor()) {
            Input input = new Input();
            input.add(
                    "{\"question\": \"Why is model conversion important?\",\"context\": \"The"
                        + " option to convert models between FARM and transformers gives freedom to"
                        + " the user and let people easily switch between frameworks.\"}");
            input.addProperty("Content-Type", "application/json");
            Output output = predictor.predict(input);
            String classification = output.getData().getAsString();
            JsonElement json = JsonUtils.GSON.fromJson(classification, JsonElement.class);
            String answer = json.getAsJsonObject().get("answer").getAsString();
            Assert.assertEquals(
                    answer,
                    "gives freedom to the user and let people easily switch between frameworks");
        }
    }

    @Test
    public void testRollingBatch() throws TranslateException, IOException, ModelException {
        Criteria<Input, Output> criteria =
                Criteria.builder()
                        .setTypes(Input.class, Output.class)
                        .optEngine("Python")
                        .optModelPath(Paths.get("src/test/resources/rolling_batch"))
                        .build();
        try (ZooModel<Input, Output> model = criteria.loadModel();
                Predictor<Input, Output> predictor = model.newPredictor()) {
            List<Output> list = new ArrayList<>();
            for (int i = 0; i < 5; ++i) {
                Input input = new Input();
                input.add(
                        "{\"inputs\": \"request"
                                + i
                                + "\", \"parameters\": {\"max_length\": "
                                + (RandomUtils.nextInt(10) + 5)
                                + "}}");
                input.addProperty("Content-Type", "application/json");
                Output output = predictor.predict(input);
                list.add(output);
            }

            Output output = list.get(4);
            ChunkedBytesSupplier cbs = (ChunkedBytesSupplier) output.getData();
            Assert.assertNull(cbs.pollChunk());
            String ret = cbs.getAsString();
            System.out.println(ret);
            Assert.assertTrue(ret.startsWith(" token_request4_"));
        }
    }

    @Test
    public void testAsyncMode()
            throws TranslateException, IOException, ModelException, InterruptedException {
        int inferenceRequestSleepTime = 5;
        Criteria<Input, Output> criteria =
                Criteria.builder()
                        .setTypes(Input.class, Output.class)
                        .optEngine("Python")
                        .optModelPath(Paths.get("src/test/resources/async_echo"))
                        .build();
        List<Output> outputList = new ArrayList<>();
        try (ZooModel<Input, Output> model = criteria.loadModel();
                Predictor<Input, Output> predictor = model.newPredictor()) {
            long startTime = System.currentTimeMillis();
            for (int i = 0; i < 5; ++i) {
                String streaming = i % 2 == 0 ? "true" : "false";
                Input input = new Input();
                input.add(
                        "{\"inputs\": \"request"
                                + i
                                + "\", \"stream\": \""
                                + streaming
                                + "\", \"parameters\": {\"sleep_time\": "
                                + inferenceRequestSleepTime
                                + "}}");
                input.addProperty("Content-Type", "application/json");
                Output output = predictor.predict(input);
                outputList.add(output);
            }
            for (int i = 0; i < outputList.size(); ++i) {
                Output output = outputList.get(i);
                ChunkedBytesSupplier cbs = (ChunkedBytesSupplier) output.getData();
                // streaming validations
                if (i % 2 == 0) {
                    String[] expectedTokens = {
                        "request" + i + " unit", "testing", "async", "mode", "[DONE]"
                    };
                    for (int j = 0; j < 5; ++j) {
                        byte[] buf = cbs.nextChunk(5, TimeUnit.SECONDS);
                        String tokenJsonStr = new String(buf, StandardCharsets.UTF_8);
                        JsonElement json = JsonUtils.GSON.fromJson(tokenJsonStr, JsonElement.class);
                        String token = json.getAsJsonObject().get("token").getAsString();
                        System.out.println(token);
                        Assert.assertEquals(token, expectedTokens[j]);
                    }
                    Assert.assertFalse(cbs.hasNext());
                } else {
                    String ret = cbs.getAsString();
                    JsonElement json = JsonUtils.GSON.fromJson(ret, JsonElement.class);
                    String generatedText =
                            json.getAsJsonObject().get("generated_text").getAsString();
                    System.out.println(generatedText);
                    Assert.assertEquals(generatedText, "request" + i + " unit testing async mode");
                }
            }
            long endTime = System.currentTimeMillis();
            long totalTime = endTime - startTime;
            // Each request is sleeping for inferenceRequestSleepTime seconds, but in "parallel".
            // The total time for 5 requests should be close to inferenceRequestSleepTime seconds.
            System.out.println(
                    "Total time for 5 requests each taking about 5 seconds is " + totalTime + "ms");
            Assert.assertTrue(totalTime < (inferenceRequestSleepTime + 1) * 1000);
        }
    }

    @Test
    public void testModelException() throws TranslateException, IOException, ModelException {
        Criteria<Input, Output> criteria =
                Criteria.builder()
                        .setTypes(Input.class, Output.class)
                        .optModelPath(Paths.get("src/test/resources/echo"))
                        .optEngine("Python")
                        .build();
        try (ZooModel<Input, Output> model = criteria.loadModel();
                Predictor<Input, Output> predictor = model.newPredictor()) {
            Input input = new Input();
            input.add("exception", "model error");
            Output output = predictor.predict(input);
            Assert.assertEquals(output.getCode(), 424);
            String ret = output.getData().getAsString();
            JsonElement json = JsonUtils.GSON.fromJson(ret, JsonElement.class);
            String error = json.getAsJsonObject().get("error").getAsString();
            Assert.assertEquals(error, "model error");

            input = new Input();
            input.add("typeerror", "type error");
            output = predictor.predict(input);
            Assert.assertEquals(output.getCode(), 424);

            // Test empty input
            input = new Input();
            input.add("exception", "");
            output = predictor.predict(input);
            Assert.assertEquals(output.getCode(), 424);
        }
    }

    @Test
    public void testRestartProcess() throws IOException, ModelException, InterruptedException {
        Criteria<Input, Output> criteria =
                Criteria.builder()
                        .setTypes(Input.class, Output.class)
                        .optModelPath(Paths.get("src/test/resources/echo"))
                        .optEngine("Python")
                        .build();
        try (ZooModel<Input, Output> model = criteria.loadModel();
                Predictor<Input, Output> predictor = model.newPredictor()) {
            Assert.assertNull(model.getProperty("failed"));
            Input input = new Input();
            input.add("exit", "true");
            Assert.assertThrows(EngineException.class, () -> predictor.predict(input));
            Assert.assertEquals(model.getProperty("failed"), "1");

            Input input2 = new Input();
            input2.add("data", "input");
            Output output = null;
            for (int i = 0; i < 5; ++i) {
                Thread.sleep(1000);
                try {
                    output = predictor.predict(input2);
                    break;
                } catch (TranslateException ignore) {
                    // ignore
                }
            }
            Assert.assertNotNull(output);
            Assert.assertEquals(output.getCode(), 200);
        }
    }
}
