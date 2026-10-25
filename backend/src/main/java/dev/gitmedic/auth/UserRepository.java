package dev.gitmedic.auth;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {

  public record User(long id, long githubId, String login, String name, String avatarUrl) {}

  private final JdbcClient jdbc;

  public UserRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public long upsert(
      long githubId, String login, String name, String avatarUrl, byte[] tokenEnc, String scopes) {
    return jdbc.sql(
            """
            insert into gitmedic.users (github_id, login, name, avatar_url, access_token_enc, token_scopes)
            values (:githubId, :login, :name, :avatarUrl, :tokenEnc, :scopes)
            on conflict (github_id) do update set login = excluded.login, name = excluded.name,
              avatar_url = excluded.avatar_url, access_token_enc = excluded.access_token_enc,
              token_scopes = excluded.token_scopes, updated_at = now()
            returning id
            """)
        .param("githubId", githubId)
        .param("login", login)
        .param("name", name)
        .param("avatarUrl", avatarUrl)
        .param("tokenEnc", tokenEnc)
        .param("scopes", scopes)
        .query(Long.class)
        .single();
  }

  public Optional<User> findById(long id) {
    return jdbc.sql(
            "select id, github_id, login, name, avatar_url from gitmedic.users where id = :id")
        .param("id", id)
        .query(User.class)
        .optional();
  }

  public Optional<byte[]> findEncryptedToken(long id) {
    return jdbc.sql("select access_token_enc from gitmedic.users where id = :id")
        .param("id", id)
        .query(byte[].class)
        .optional();
  }
}
