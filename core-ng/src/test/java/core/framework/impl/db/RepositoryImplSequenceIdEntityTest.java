package core.framework.impl.db;

import core.framework.api.db.Query;
import core.framework.api.db.Repository;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertEquals;

/**
 * @author neo
 */
public class RepositoryImplSequenceIdEntityTest {
    private static DatabaseImpl database;
    private static Repository<SequenceIdEntity> repository;

    @BeforeClass
    public static void createDatabase() {
        database = new DatabaseImpl();
        database.url("jdbc:hsqldb:mem:seq;sql.syntax_ora=true");
        database.vendor = Vendor.ORACLE;
        database.execute("CREATE TABLE sequence_id_entity (id VARCHAR(36) PRIMARY KEY, string_field VARCHAR(20), long_field BIGINT)");
        database.execute("CREATE SEQUENCE seq");

        repository = database.repository(SequenceIdEntity.class);
    }

    @AfterClass
    public static void cleanupDatabase() {
        database.execute("DROP TABLE sequence_id_entity");
        database.execute("DROP SEQUENCE seq");
    }

    @Before
    public void truncateTable() {
        database.execute("TRUNCATE TABLE sequence_id_entity");
    }

    @Test
    public void insert() {
        SequenceIdEntity entity = new SequenceIdEntity();
        entity.stringField = "string";

        Optional<Long> id = repository.insert(entity);
        Assert.assertTrue(id.isPresent());

        SequenceIdEntity selectedEntity = repository.get(id.get()).get();

        assertEquals((long) id.get(), (long) selectedEntity.id);
        assertEquals(entity.stringField, selectedEntity.stringField);
    }

    @Test
    public void count() {
        createEntities();

        Query<SequenceIdEntity> query = repository.select();
        assertEquals(30, query.count());

        query.where("string_field like ?", "value2%");
        assertEquals(11, query.count());
    }

    @Test
    public void select() {
        createEntities();

        Query<SequenceIdEntity> query = repository.select().orderBy("long_field").limit(5);

        List<SequenceIdEntity> entities = query.skip(0).fetch();
        assertEquals(5, entities.size());
        assertEquals("value1", entities.get(0).stringField);
        assertEquals(Long.valueOf(1), entities.get(0).longField);
        assertEquals(Long.valueOf(5), entities.get(4).longField);

        entities = query.skip(5).fetch();
        assertEquals(5, entities.size());
        assertEquals(Long.valueOf(6), entities.get(0).longField);
        assertEquals(Long.valueOf(10), entities.get(4).longField);

        entities = query.skip(10).fetch();
        assertEquals(5, entities.size());
        assertEquals(Long.valueOf(11), entities.get(0).longField);
        assertEquals(Long.valueOf(15), entities.get(4).longField);

        query.where("long_field > ?", 10);

        entities = query.skip(0).fetch();
        assertEquals(5, entities.size());
        assertEquals("value11", entities.get(0).stringField);
        assertEquals(Long.valueOf(11), entities.get(0).longField);
        assertEquals(Long.valueOf(15), entities.get(4).longField);

        entities = query.skip(5).fetch();
        assertEquals(5, entities.size());
        assertEquals(Long.valueOf(16), entities.get(0).longField);
        assertEquals(Long.valueOf(20), entities.get(4).longField);
    }

    private void createEntities() {
        for (int i = 1; i <= 30; i++) {
            SequenceIdEntity entity = new SequenceIdEntity();
            entity.stringField = "value" + i;
            entity.longField = (long) i;
            repository.insert(entity);
        }
    }
}