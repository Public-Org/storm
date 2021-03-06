package org.apache.storm.hdfs.bolt;

import backtype.storm.Config;
import backtype.storm.task.GeneralTopologyContext;
import backtype.storm.task.OutputCollector;
import backtype.storm.task.TopologyContext;
import backtype.storm.topology.TopologyBuilder;
import backtype.storm.tuple.Fields;
import backtype.storm.tuple.Tuple;
import backtype.storm.tuple.TupleImpl;
import backtype.storm.tuple.Values;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.DatumReader;
import org.apache.avro.file.DataFileReader;
import org.apache.storm.hdfs.bolt.format.DefaultFileNameFormat;
import org.apache.storm.hdfs.bolt.format.FileNameFormat;
import org.apache.storm.hdfs.bolt.rotation.FileRotationPolicy;
import org.apache.storm.hdfs.bolt.rotation.FileSizeRotationPolicy;
import org.apache.storm.hdfs.bolt.sync.CountSyncPolicy;
import org.apache.storm.hdfs.bolt.sync.SyncPolicy;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.Assert;

import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileUtil;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.apache.hadoop.fs.FSDataInputStream;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;

public class AvroGenericRecordBoltTest {

    private String hdfsURI;
    private DistributedFileSystem fs;
    private MiniDFSCluster hdfsCluster;
    private static final String testRoot = "/unittest";
    private static final Schema schema;
    private static final Tuple tuple1;
    private static final Tuple tuple2;
    private static final String userSchema = "{\"type\":\"record\"," +
            "\"name\":\"myrecord\"," +
            "\"fields\":[{\"name\":\"foo1\",\"type\":\"string\"}," +
            "{ \"name\":\"int1\", \"type\":\"int\" }]}";

    static {

        Schema.Parser parser = new Schema.Parser();
        schema = parser.parse(userSchema);

        GenericRecord record1 = new GenericData.Record(schema);
        record1.put("foo1", "bar1");
        record1.put("int1", 1);
        tuple1 = generateTestTuple(record1);

        GenericRecord record2 = new GenericData.Record(schema);
        record2.put("foo1", "bar2");
        record2.put("int1", 2);
        tuple2 = generateTestTuple(record2);
    }

    @Mock private OutputCollector collector;
    @Mock private TopologyContext topologyContext;

    @Before
    public void setup() throws Exception {
        MockitoAnnotations.initMocks(this);
        Configuration conf = new Configuration();
        conf.set("fs.trash.interval", "10");
        conf.setBoolean("dfs.permissions", true);
        File baseDir = new File("./target/hdfs/").getAbsoluteFile();
        FileUtil.fullyDelete(baseDir);
        conf.set(MiniDFSCluster.HDFS_MINIDFS_BASEDIR, baseDir.getAbsolutePath());

        MiniDFSCluster.Builder builder = new MiniDFSCluster.Builder(conf);
        hdfsCluster = builder.build();
        fs = hdfsCluster.getFileSystem();
        hdfsURI = fs.getUri() + "/";
    }

    @After
    public void shutDown() throws IOException {
        fs.close();
        hdfsCluster.shutdown();
    }

    @Test public void multipleTuplesOneFile() throws IOException
    {
        AvroGenericRecordBolt bolt = makeAvroBolt(hdfsURI, 1, 1f, userSchema);

        bolt.prepare(new Config(), topologyContext, collector);
        bolt.execute(tuple1);
        bolt.execute(tuple2);
        bolt.execute(tuple1);
        bolt.execute(tuple2);

        Assert.assertEquals(1, countNonZeroLengthFiles(testRoot));
        verifyAllAvroFiles(testRoot, schema);
    }

    @Test public void multipleTuplesMutliplesFiles() throws IOException
    {
        AvroGenericRecordBolt bolt = makeAvroBolt(hdfsURI, 1, .0001f, userSchema);

        bolt.prepare(new Config(), topologyContext, collector);
        bolt.execute(tuple1);
        bolt.execute(tuple2);
        bolt.execute(tuple1);
        bolt.execute(tuple2);

        Assert.assertEquals(4, countNonZeroLengthFiles(testRoot));
        verifyAllAvroFiles(testRoot, schema);
    }

    private AvroGenericRecordBolt makeAvroBolt(String nameNodeAddr, int countSync, float rotationSizeMB, String schemaAsString) {

        SyncPolicy fieldsSyncPolicy = new CountSyncPolicy(countSync);

        FileNameFormat fieldsFileNameFormat = new DefaultFileNameFormat().withPath(testRoot);

        FileRotationPolicy rotationPolicy =
                new FileSizeRotationPolicy(rotationSizeMB, FileSizeRotationPolicy.Units.MB);

        return new AvroGenericRecordBolt()
                .withFsUrl(nameNodeAddr)
                .withFileNameFormat(fieldsFileNameFormat)
                .withSchemaAsString(schemaAsString)
                .withRotationPolicy(rotationPolicy)
                .withSyncPolicy(fieldsSyncPolicy);
    }

    private static Tuple generateTestTuple(GenericRecord record) {
        TopologyBuilder builder = new TopologyBuilder();
        GeneralTopologyContext topologyContext = new GeneralTopologyContext(builder.createTopology(),
                new Config(), new HashMap(), new HashMap(), new HashMap(), "") {
            @Override
            public Fields getComponentOutputFields(String componentId, String streamId) {
                return new Fields("record");
            }
        };
        return new TupleImpl(topologyContext, new Values(record), 1, "");
    }

    private void verifyAllAvroFiles(String path, Schema schema) throws IOException {
        Path p = new Path(path);

        for (FileStatus file : fs.listStatus(p)) {
            if (file.getLen() > 0) {
                fileIsGoodAvro(file.getPath(), schema);
            }
        }
    }

    private int countNonZeroLengthFiles(String path) throws IOException {
        Path p = new Path(path);
        int nonZero = 0;

        for (FileStatus file : fs.listStatus(p)) {
            if (file.getLen() > 0) {
                nonZero++;
            }
        }

        return nonZero;
    }

    private void fileIsGoodAvro (Path path, Schema schema) throws IOException {
        DatumReader<GenericRecord> datumReader = new GenericDatumReader<>(schema);
        FSDataInputStream in = fs.open(path, 0);
        FileOutputStream out = new FileOutputStream("target/FOO.avro");

        byte[] buffer = new byte[100];
        int bytesRead;
        while ((bytesRead = in.read(buffer)) > 0) {
            out.write(buffer, 0, bytesRead);
        }
        out.close();

        java.io.File file = new File("target/FOO.avro");

        DataFileReader<GenericRecord> dataFileReader = new DataFileReader<>(file, datumReader);
        GenericRecord user = null;
        while (dataFileReader.hasNext()) {
            user = dataFileReader.next(user);
            System.out.println(user);
        }

        file.delete();
    }
}
