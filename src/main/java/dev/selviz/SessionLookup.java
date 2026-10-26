package dev.selviz;

import com.sk89q.worldedit.session.SessionKey;
import com.sk89q.worldedit.session.SessionOwner;
import com.sk89q.worldedit.util.auth.AuthorizationException;

import java.util.UUID;

/**
 * Ключ для поиска сессии WorldEdit по UUID игрока.
 *
 * <p>{@code SessionManager#getIfPresent} ищет сессию в словаре по UUID ключа, то есть
 * за O(1). {@code findByName} перебирал бы все сессии, включая сессии вышедших игроков,
 * и мог бы найти чужую сессию, если ник сменил владельца.</p>
 *
 * <p>Используется только для чтения: никаких прав этот «владелец» не имеет.</p>
 */
record SessionLookup(UUID uuid, String name) implements SessionOwner, SessionKey {

    @Override
    public SessionKey getSessionKey() {
        return this;
    }

    @Override
    public UUID getUniqueId() {
        return this.uuid;
    }

    @Override
    public String getName() {
        return this.name;
    }

    @Override
    public boolean isActive() {
        return true;
    }

    @Override
    public boolean isPersistent() {
        return false;
    }

    @Override
    public String[] getGroups() {
        return new String[0];
    }

    @Override
    public void checkPermission(final String permission) throws AuthorizationException {
        throw new AuthorizationException();
    }

    @Override
    public boolean hasPermission(final String permission) {
        return false;
    }
}
