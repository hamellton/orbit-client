package orbit.secrets;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encrypted local store of account credentials.
 *
 * <p>File layout: {@code "OVLT"}, version byte, PBKDF2 iteration count (int), 16-byte salt,
 * 12-byte GCM nonce, then AES-256-GCM ciphertext of a JSON object {@code name → Account}. The
 * header is authenticated as additional data. Every save uses a fresh nonce and is written to a
 * temporary file first, then moved over the old one.
 *
 * <p>Thread-safe: several accounts of one program share it.
 */
public final class Vault {

    private static final byte[] MAGIC = "OVLT".getBytes(StandardCharsets.US_ASCII);
    private static final byte VERSION = 1;
    static final int DEFAULT_ITERATIONS = 600_000;
    private static final int SALT_LEN = 16;
    private static final int NONCE_LEN = 12;
    private static final int HEADER_LEN = MAGIC.length + 1 + 4 + SALT_LEN + NONCE_LEN;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path file;
    private final int iterations;
    private final byte[] salt;
    private final SecretKey key;
    private final Map<String, Account> accounts;

    private Vault(Path file, int iterations, byte[] salt, SecretKey key, Map<String, Account> accounts) {
        this.file = file;
        this.iterations = iterations;
        this.salt = salt;
        this.key = key;
        this.accounts = accounts;
        accounts.values().forEach(Account::registerSecrets);
    }

    /** Opens an existing vault, or prepares an empty one (written on the first {@link #save()}). */
    public static Vault open(Path file, char[] masterPassword) throws VaultException {
        return open(file, masterPassword, DEFAULT_ITERATIONS);
    }

    static Vault open(Path file, char[] masterPassword, int iterationsForNew) throws VaultException {
        if (masterPassword == null || masterPassword.length == 0) {
            throw new VaultException("Мастер-пароль не может быть пустым.");
        }
        if (!Files.exists(file)) {
            byte[] salt = new byte[SALT_LEN];
            RANDOM.nextBytes(salt);
            return new Vault(file, iterationsForNew, salt, deriveKey(masterPassword, salt, iterationsForNew), new TreeMap<>());
        }
        byte[] data;
        try {
            data = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new VaultException("Не удалось прочитать хранилище " + file + ": " + e.getMessage(), e);
        }
        if (data.length < HEADER_LEN + 16 || !Arrays.equals(Arrays.copyOf(data, MAGIC.length), MAGIC)) {
            throw new VaultException("Файл " + file + " не является хранилищем бота.");
        }
        ByteBuffer header = ByteBuffer.wrap(data, MAGIC.length, HEADER_LEN - MAGIC.length);
        byte version = header.get();
        if (version != VERSION) {
            throw new VaultException("Хранилище создано другой версией бота (формат " + version + ").");
        }
        int iterations = header.getInt();
        if (iterations < 10_000 || iterations > 10_000_000) {
            throw new VaultException("Хранилище повреждено (число итераций " + iterations + ").");
        }
        byte[] salt = new byte[SALT_LEN];
        byte[] nonce = new byte[NONCE_LEN];
        header.get(salt).get(nonce);
        SecretKey key = deriveKey(masterPassword, salt, iterations);
        byte[] plain;
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            c.updateAAD(data, 0, HEADER_LEN);
            plain = c.doFinal(data, HEADER_LEN, data.length - HEADER_LEN);
        } catch (AEADBadTagException e) {
            throw new VaultException("Неверный мастер-пароль или хранилище повреждено.", e);
        } catch (GeneralSecurityException e) {
            throw new VaultException("Ошибка расшифровки хранилища: " + e.getMessage(), e);
        }
        try {
            Map<String, Account> accounts = JSON.readValue(plain, new TypeReference<TreeMap<String, Account>>() { });
            return new Vault(file, iterations, salt, key, accounts);
        } catch (IOException e) {
            throw new VaultException("Хранилище повреждено (содержимое не читается).", e);
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    public Path file() {
        return file;
    }

    /** Account names in alphabetical order. */
    public synchronized Map<String, Account> accounts() {
        return Collections.unmodifiableMap(accounts);
    }

    public synchronized Account get(String name) {
        return accounts.get(name);
    }

    /** Adds or replaces an account; call {@link #save()} to persist. */
    public synchronized void put(String name, Account account) {
        if (name == null || !name.matches("[\\p{L}\\p{N}_.-]{1,64}")) {
            throw new IllegalArgumentException("Имя аккаунта: буквы, цифры, «_», «.», «-», до 64 символов.");
        }
        account.registerSecrets();
        accounts.put(name, account);
    }

    public synchronized boolean remove(String name) {
        return accounts.remove(name) != null;
    }

    /** Encrypts and writes the vault atomically; the file is readable only by the owner where supported. */
    public synchronized void save() throws VaultException {
        byte[] nonce = new byte[NONCE_LEN];
        RANDOM.nextBytes(nonce);
        ByteBuffer header = ByteBuffer.allocate(HEADER_LEN).put(MAGIC).put(VERSION).putInt(iterations).put(salt).put(nonce);
        byte[] plain = null;
        try {
            plain = JSON.writeValueAsBytes(accounts);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            c.updateAAD(header.array());
            byte[] cipher = c.doFinal(plain);
            byte[] out = ByteBuffer.allocate(HEADER_LEN + cipher.length).put(header.array()).put(cipher).array();

            Path dir = file.toAbsolutePath().getParent();
            Files.createDirectories(dir);
            Path tmp = Files.createTempFile(dir, ".vault", ".tmp");
            try {
                restrictToOwner(tmp);
                Files.write(tmp, out);
                try {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException | GeneralSecurityException e) {
            throw new VaultException("Не удалось сохранить хранилище " + file + ": " + e.getMessage(), e);
        } finally {
            if (plain != null) {
                Arrays.fill(plain, (byte) 0);
            }
        }
    }

    private static void restrictToOwner(Path p) {
        try {
            Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows: the file stays under the user's profile ACLs
        }
    }

    private static SecretKey deriveKey(char[] password, byte[] salt, int iterations) throws VaultException {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, 256);
        try {
            byte[] raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            SecretKey key = new SecretKeySpec(raw, "AES");
            Arrays.fill(raw, (byte) 0);
            return key;
        } catch (GeneralSecurityException e) {
            throw new VaultException("Не удалось получить ключ хранилища: " + e.getMessage(), e);
        } finally {
            spec.clearPassword();
        }
    }
}
