package com.nexusai.apis.db;

import com.nexusai.model.database.dto.DatabaseConnectionDto;
import com.nexusai.model.database.dto.DatabaseCreateRequest;
import com.nexusai.model.provider.dto.TestConnectionResponse;
import com.nexusai.domain.db.DatabaseConnectionService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Database Connection REST 端点 */
@RestController
@RequestMapping("/api/v1/databases")
public class DatabaseConnectionController {

    @Autowired private DatabaseConnectionService databaseConnectionService;

    @GetMapping
    public List<DatabaseConnectionDto> list() {
        return databaseConnectionService.listAll();
    }

    @GetMapping("/{id}")
    public DatabaseConnectionDto get(@PathVariable String id) {
        return databaseConnectionService.getById(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DatabaseConnectionDto create(@Valid @RequestBody DatabaseCreateRequest req) {
        return databaseConnectionService.create(req);
    }

    @PatchMapping("/{id}")
    public DatabaseConnectionDto update(@PathVariable String id,
                                        @RequestBody DatabaseCreateRequest req) {
        return databaseConnectionService.update(id, req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        databaseConnectionService.delete(id);
    }

    @PostMapping("/{id}/test")
    public TestConnectionResponse test(@PathVariable String id) {
        return databaseConnectionService.test(id);
    }
}
