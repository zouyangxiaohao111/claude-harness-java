package com.nexusai.domain.db;

import com.nexusai.model.database.dto.DatabaseConnectionDto;
import com.nexusai.model.database.dto.DatabaseCreateRequest;
import com.nexusai.model.database.dto.DatabaseStatus;
import com.nexusai.model.database.dto.DatabaseType;
import com.nexusai.model.provider.dto.TestConnectionResponse;
import com.nexusai.repository.db.entity.DatabaseConnectionRecord;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.repository.db.mapper.DatabaseConnectionMapper;
import com.nexusai.infra.util.ApiKeyHasher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * DatabaseConnection 业务逻辑：
 * - list / getById：返回时 passwordMasked 恒为 "****"
 * - create：hash 密码 + status=disconnected
 * - update：PATCH 语义
 * - delete
 * - test：stub
 */
@Service
public class DatabaseConnectionService {

    private static final String MASK = "****";

    @Autowired private DatabaseConnectionMapper databaseConnectionMapper;

    public List<DatabaseConnectionDto> listAll() {
        List<DatabaseConnectionRecord> all = databaseConnectionMapper.selectAll();
        return all.stream().map(this::toDto).toList();
    }

    public DatabaseConnectionDto getById(String id) {
        DatabaseConnectionRecord d = databaseConnectionMapper.selectOneById(id);
        if (d == null) throw new NotFoundException("Database connection " + id + " not found");
        return toDto(d);
    }

    public DatabaseConnectionDto create(DatabaseCreateRequest req) {
        DatabaseConnectionRecord d = new DatabaseConnectionRecord();
        d.setId(generateId("db"));
        d.setName(req.name());
        d.setType(req.type() != null ? req.type().name() : DatabaseType.postgres.name());
        d.setHost(req.host());
        d.setPort(req.port());
        d.setDatabase(req.database());
        d.setUser(req.user());
        if (req.password() != null && !req.password().isEmpty()) {
            d.setPasswordHash(ApiKeyHasher.hash(req.password()));
        }
        d.setStatus(DatabaseStatus.disconnected.name());
        d.setLastError(null);

        databaseConnectionMapper.insert(d);
        return toDto(d);
    }

    public DatabaseConnectionDto update(String id, DatabaseCreateRequest req) {
        DatabaseConnectionRecord d = databaseConnectionMapper.selectOneById(id);
        if (d == null) throw new NotFoundException("Database connection " + id + " not found");

        if (req.name() != null) d.setName(req.name());
        if (req.type() != null) d.setType(req.type().name());
        if (req.host() != null) d.setHost(req.host());
        if (req.port() != null) d.setPort(req.port());
        if (req.database() != null) d.setDatabase(req.database());
        if (req.user() != null) d.setUser(req.user());
        if (req.password() != null && !req.password().isEmpty()) {
            d.setPasswordHash(ApiKeyHasher.hash(req.password()));
        }

        databaseConnectionMapper.update(d);
        return toDto(d);
    }

    public void delete(String id) {
        DatabaseConnectionRecord d = databaseConnectionMapper.selectOneById(id);
        if (d == null) throw new NotFoundException("Database connection " + id + " not found");
        databaseConnectionMapper.deleteById(id);
    }

    public TestConnectionResponse test(String id) {
        DatabaseConnectionRecord d = databaseConnectionMapper.selectOneById(id);
        if (d == null) throw new NotFoundException("Database connection " + id + " not found");
        return new TestConnectionResponse(true, 23L, "已连接 (latency 23ms)", null);
    }

    // ============== helpers ==============

    private DatabaseConnectionDto toDto(DatabaseConnectionRecord d) {
        return new DatabaseConnectionDto(
            d.getId(),
            d.getName(),
            d.getType() != null ? DatabaseType.valueOf(d.getType()) : null,
            d.getHost(),
            d.getPort(),
            d.getDatabase(),
            d.getUser(),
            MASK,                                        // 永远 "****"
            d.getStatus() != null ? DatabaseStatus.valueOf(d.getStatus()) : DatabaseStatus.disconnected,
            d.getLastError()
        );
    }

    private static String generateId(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
