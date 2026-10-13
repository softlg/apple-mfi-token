package com.galaplat.mfi.identity.infrastructure;

import com.galaplat.mfi.identity.application.AccountReader;
import com.galaplat.mfi.identity.infrastructure.persistence.UserMapper;
import com.galaplat.mfi.identity.infrastructure.persistence.UserPO;
import org.springframework.stereotype.Component;

/**
 * 账号展示名读取：姓名优先，账号兜底。
 *
 * <p>账号被删（或历史数据查不到）时返回占位而不是抛错——申请单与留痕要能继续打开，
 * 发起人已经不存在不该让整张单据变成 500。
 */
@Component
public class AccountReaderAdapter implements AccountReader {

    private final UserMapper userMapper;

    public AccountReaderAdapter(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Override
    public String displayName(long userId) {
        UserPO user = userMapper.selectById(userId);
        if (user == null) {
            return "已注销账号";
        }
        return user.getName() == null || user.getName().isBlank() ? user.getAccount() : user.getName().trim();
    }

}
