package com.galaplat.mfi.issuance.application.pipeline.handler;

import com.galaplat.mfi.apple.MfiTokenClient;
import com.galaplat.mfi.apple.model.BulkUsedAuthEntityRequest;
import com.galaplat.mfi.apple.model.MfiResponse;
import com.galaplat.mfi.credential.infrastructure.client.MfiClientFactory;
import com.galaplat.mfi.issuance.domain.Stage;
import com.galaplat.mfi.identity.domain.TenantConfigKey;
import com.galaplat.mfi.shared.api.BizException;
import com.galaplat.mfi.issuance.infrastructure.persistence.ApplicationPO;
import com.galaplat.mfi.issuance.infrastructure.persistence.ApplicationPpidPO;
import com.galaplat.mfi.issuance.infrastructure.persistence.TokenPO;
import com.galaplat.mfi.issuance.infrastructure.persistence.TokenMapper;
import com.galaplat.mfi.issuance.domain.StageContext;
import com.galaplat.mfi.issuance.domain.StageOutcome;
import com.galaplat.mfi.identity.application.TenantConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * REGISTER 阶段。注册在苹果侧不可逆，所以这里只保证三件事：批次大小不超过苹果上限、
 * 提交的是库里已有的 accessory_uuid、以及失败只标记未注册成功的那一批。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RegisterStageHandlerTest {

    private static final long TENANT_ID = 7L;
    private static final long APPLICATION_ID = 555L;
    private static final long SHARD_ID = 901L;
    private static final String PPID = "C00EXAMPLEPPID";

    @Mock
    private MfiClientFactory clientFactory;
    @Mock
    private MfiTokenClient client;
    @Mock
    private TokenMapper tokenMapper;
    @Mock
    private TenantConfigService configService;

    private RegisterStageHandler handler;
    private ApplicationPpidPO shard;

    @BeforeEach
    void setUp() throws IOException {
        handler = new RegisterStageHandler(clientFactory, tokenMapper, configService);
        shard = new ApplicationPpidPO();
        shard.setId(SHARD_ID);
        shard.setTenantId(TENANT_ID);
        shard.setApplicationId(APPLICATION_ID);
        shard.setPpid(PPID);
        shard.setStage(Stage.REGISTER.name());
        when(clientFactory.forTenant(TENANT_ID)).thenReturn(client);
        when(configService.intValue(TENANT_ID, TenantConfigKey.APPLE_REGISTER_BATCH_SIZE)).thenReturn(2);
        when(client.registerUsedAuthEntitiesBulk(eq(PPID), anyList())).thenReturn(new MfiResponse());
    }

    @Test
    void nothingToRegisterAdvancesWithoutApple() throws IOException {
        when(tokenMapper.selectUnregistered(TENANT_ID, SHARD_ID)).thenReturn(List.of());

        StageOutcome outcome = handler.handle(context());

        assertEquals(StageOutcome.Kind.ADVANCE, outcome.kind());
        verifyNoInteractions(client);
    }

    @Test
    void tokensAreRegisteredInBatchesAndMarkedPerBatch() throws IOException {
        when(tokenMapper.selectUnregistered(TENANT_ID, SHARD_ID))
                .thenReturn(tokens(1L, 2L, 3L));

        StageOutcome outcome = handler.handle(context());

        assertEquals(StageOutcome.Kind.ADVANCE, outcome.kind());
        // 每批成功即单独落库：下一次重试只补没成功的那几批
        verify(client, times(2)).registerUsedAuthEntitiesBulk(eq(PPID), anyList());
        verify(tokenMapper).markRegistered(TENANT_ID, List.of(1L, 2L));
        verify(tokenMapper).markRegistered(TENANT_ID, List.of(3L));
        verify(tokenMapper, never()).markRegisterFailed(anyLong(), anyList());
    }

    @Test
    @SuppressWarnings("unchecked")
    void eachEntryCarriesOneTokenAndItsStoredUuid() throws IOException {
        when(tokenMapper.selectUnregistered(TENANT_ID, SHARD_ID)).thenReturn(tokens(1L, 2L));

        handler.handle(context());

        ArgumentCaptor<List<Map<String, String>>> batches = ArgumentCaptor.forClass(List.class);
        verify(client).registerUsedAuthEntitiesBulk(eq(PPID), batches.capture());
        List<Map<String, String>> entities = batches.getValue();
        assertEquals(2, entities.size());
        // 苹果的 auth_entities 是"每项只有一对"的数组：key 为 token 编号，value 为配件 UUID
        assertEquals(Map.of("token-1", "uuid-1"), entities.get(0));
        assertEquals(1, entities.get(1).size());
    }

    @Test
    void batchIsCappedAtTheAppleLimit() throws IOException {
        when(configService.intValue(TENANT_ID, TenantConfigKey.APPLE_REGISTER_BATCH_SIZE))
                .thenReturn(Integer.MAX_VALUE);
        List<TokenPO> many = new ArrayList<>();
        for (long id = 1; id <= BulkUsedAuthEntityRequest.MAX_BATCH_SIZE + 1L; id++) {
            many.add(token(id));
        }
        when(tokenMapper.selectUnregistered(TENANT_ID, SHARD_ID)).thenReturn(many);

        handler.handle(context());

        ArgumentCaptor<List<Map<String, String>>> batches = ArgumentCaptor.forClass(List.class);
        verify(client, times(2)).registerUsedAuthEntitiesBulk(eq(PPID), batches.capture());
        assertEquals(BulkUsedAuthEntityRequest.MAX_BATCH_SIZE, batches.getAllValues().get(0).size());
        assertEquals(1, batches.getAllValues().get(1).size());
    }

    @Test
    void missingUuidIsPermanentFailure() throws IOException {
        TokenPO broken = token(1L);
        broken.setAccessoryUUID(" ");
        when(tokenMapper.selectUnregistered(TENANT_ID, SHARD_ID)).thenReturn(List.of(broken));

        // 没有配件 UUID 就无法注册，而苹果的注册不可逆，不能在这里临时生成一个
        assertThrows(BizException.class, () -> handler.handle(context()));
        verify(client, never()).registerUsedAuthEntitiesBulk(anyString(), anyList());
    }

    @Test
    void rejectedBatchMarksFailureAndRetries() throws IOException {
        when(tokenMapper.selectUnregistered(TENANT_ID, SHARD_ID)).thenReturn(tokens(1L, 2L));
        MfiResponse failure = new MfiResponse();
        failure.setCode(500);
        failure.setErrorMessage("apple unavailable");
        when(client.registerUsedAuthEntitiesBulk(eq(PPID), anyList())).thenReturn(failure);

        assertThrows(IOException.class, () -> handler.handle(context()));

        verify(tokenMapper).markRegisterFailed(eq(TENANT_ID), eq(List.of(1L, 2L)));
        verify(tokenMapper, never()).markRegistered(anyLong(), anyList());
    }

    private TokenPO token(long id) {
        TokenPO token = new TokenPO();
        token.setId(id);
        token.setTenantId(TENANT_ID);
        token.setShardId(SHARD_ID);
        token.setPpid(PPID);
        token.setAuthEntityId("token-" + id);
        token.setAccessoryUUID("uuid-" + id);
        return token;
    }

    private List<TokenPO> tokens(long... ids) {
        List<TokenPO> rows = new ArrayList<>(ids.length);
        for (long id : ids) {
            rows.add(token(id));
        }
        return rows;
    }

    private StageContext context() {
        ApplicationPO application = new ApplicationPO();
        application.setId(APPLICATION_ID);
        application.setApplicationNo("A261010-ABCDEFGH");
        return new StageContext(TENANT_ID, Stage.REGISTER, application, shard, "A261010-ABCDEFGH");
    }

}
