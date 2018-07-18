package com._4paradigm.rtidb.client.impl;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.util.List;
import java.util.concurrent.TimeoutException;

import com._4paradigm.rtidb.client.KvIterator;
import com._4paradigm.rtidb.client.TabletException;
import com._4paradigm.rtidb.client.ha.PartitionHandler;
import com._4paradigm.rtidb.client.ha.RTIDBClient;
import com._4paradigm.rtidb.client.ha.RTIDBClientConfig;
import com._4paradigm.rtidb.client.ha.TableHandler;
import com._4paradigm.rtidb.client.metrics.TabletMetrics;
import com._4paradigm.rtidb.client.schema.ColumnDesc;
import com._4paradigm.rtidb.client.schema.RowCodec;
import com.google.protobuf.ByteString;
import rtidb.api.TabletServer;
import com._4paradigm.rtidb.tablet.Tablet;

public class TraverseKvIterator implements KvIterator {
    private String tname;
    private int pid = 0;
    private String idxName = null;
    private ByteString bs;
    private int offset;
    private ByteBuffer bb;

    private ByteBuffer slice;
    private int totalSize;
    private long time;
    private String pk;
    private List<ColumnDesc> schema;

    private boolean isFinished = false;

    private RTIDBClientConfig config = null;
    private RTIDBClient client = null;
    private static Charset charset = Charset.forName("utf-8");

    public TraverseKvIterator(RTIDBClient client, String tname, List<ColumnDesc> schema, String idxName) {
        this.offset = 0;
        this.totalSize = 0;
        this.client = client;
        this.tname = tname;
        this.schema = schema;
        this.idxName = idxName;
        this.isFinished = false;
    }

    private void getData() throws TimeoutException, TabletException {
        TableHandler th = client.getHandler(tname);
        if (th == null) {
            throw new TabletException("no table with name " + tname);
        }
        if (pid >= th.getPartitions().length) {
            throw new TabletException("traverse invalid pid");
        }
        PartitionHandler ph = th.getHandler(pid);
        TabletServer ts = ph.getReadHandler(th.getReadStrategy());
        Tablet.TraverseRequest.Builder builder = Tablet.TraverseRequest.newBuilder();
        builder.setTid(th.getTableInfo().getTid());
        builder.setPid(pid);
        builder.setLimit(client.getConfig().getTraverseLimit());
        if (idxName != null && !idxName.isEmpty()) {
            builder.setIdxName(idxName);
        }
        if (client.getConfig().isRemoveDuplicateByTime()) {
            builder.setEnableRemoveDuplicatedRecord(true);
        }
        if (offset != 0) {
            builder.setPk(pk);
            builder.setTs(time);
            offset = 0;
        }
        Tablet.TraverseRequest request = builder.build();
        Tablet.TraverseResponse response = ts.traverse(request);
        if (response != null && response.getCode() == 0) {
            bs = response.getPairs();
            bb = bs.asReadOnlyByteBuffer();
            totalSize = this.bs.size();
            if (response.getCount() < client.getConfig().getTraverseLimit()) {
                pid++;
                if (pid >= th.getPartitions().length) {
                    isFinished = true;
                }
            }
			return;
        }
		if (response != null) {
            throw new TabletException(response.getMsg());
        }
        throw new TabletException("rtidb internal server error");
    }

    @Override
    public int getCount() {
        return 0;
    }

    @Override
    public List<ColumnDesc> getSchema() {
        return schema;
    }

    @Override
    public boolean valid() {
        if (offset <= totalSize) {
            return true;
        }
        return false;
    }

    @Override
    public long getKey() {
        return time;
    }

    @Override
    public String getPK() {
        return pk;
    }

    @Override
    public ByteBuffer getValue() {
        return slice;
    }

    @Override
    public Object[] getDecodedValue() throws TabletException {
        if (schema == null) {
            throw new TabletException("get decoded value is not supported");
        }
        Object[] row = new Object[schema.size()];
        getDecodedValue(row, 0, row.length);
        return row;
    }

    @Override
    public void getDecodedValue(Object[] row, int start, int length) throws TabletException {
        long delta = 0l;
        if (schema == null) {
            throw new TabletException("get decoded value is not supported");
        }
        RowCodec.decode(slice, schema, row, start, length);
    }

    @Override
    public void next() {
        if (offset >= totalSize && !isFinished) {
            try {
                getData();
            } catch(Exception e){
                throw new RuntimeException(e.getMessage());
            }
        }
        if (offset + 8 > totalSize) {
            offset += 8;
            return;
        }
        slice = this.bb.slice().asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
        slice.position(offset);
        int total_size = slice.getInt();
        int pk_size = slice.getInt();
        time = slice.getLong();

        if (pk_size <= 0 || total_size - 8 - pk_size <= 0) {
            throw new RuntimeException("bad frame data");
        }
        byte[] pk_buf = new byte[pk_size];
        slice.get(pk_buf);
        pk = new String(pk_buf, charset);
        offset += (8 + total_size);
        slice.limit(offset);
    }
}
