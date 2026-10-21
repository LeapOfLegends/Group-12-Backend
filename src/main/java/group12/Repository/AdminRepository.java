package group12.Repository;

import group12.Entities.AdminEntity;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Optional;

@Mapper
public interface AdminRepository {


    @Select("""
        SELECT
            admin_id AS adminId,
            first_name AS firstName,
            last_name AS lastName,
            email,
            password_hash AS passwordHash,
            roles AS role,
            created_at AS createdAt
        FROM admins
        ORDER BY admin_id
        """)
    List<AdminEntity> findAll();

    @Select("""
        SELECT
            admin_id AS adminId,
            first_name AS firstName,
            last_name AS lastName,
            email,
            password_hash AS passwordHash,
            roles AS role,
            created_at AS createdAt
        FROM admins
        WHERE (#{search} IS NULL OR #{search} = ''
               OR first_name ILIKE CONCAT('%', #{search}, '%')
               OR last_name ILIKE CONCAT('%', #{search}, '%')
               OR email ILIKE CONCAT('%', #{search}, '%'))
          AND (#{role} IS NULL OR #{role} = '' OR roles = #{role})
        ORDER BY admin_id
        """)
    List<AdminEntity> search(
            @Param("search") String search,
            @Param("role") String role
    );


    @Select("""
        SELECT
            admin_id AS adminId,
            first_name AS firstName,
            last_name AS lastName,
            email,
            password_hash AS passwordHash,
            roles AS role,
            created_at AS createdAt
        FROM admins
        WHERE admin_id = #{adminId}
        """)
    Optional<AdminEntity> findById(Long adminId);


    @Select("""
        SELECT
            admin_id AS adminId,
            first_name AS firstName,
            last_name AS lastName,
            email,
            password_hash AS passwordHash,
            roles AS role,
            created_at AS createdAt
        FROM admins
        WHERE email = #{email}
        """)
    Optional<AdminEntity> findByEmail(String email);


    @Insert("""
        INSERT INTO admins (
            first_name,
            last_name,
            email,
            password_hash,
            roles
        )
        VALUES (
            #{firstName},
            #{lastName},
            #{email},
            #{passwordHash},
            #{role}
        )
        """)
    @Options(
            useGeneratedKeys = true,
            keyProperty = "adminId",
            keyColumn = "admin_id"
    )
    int save(AdminEntity admin);


    @Update("""
        UPDATE admins
        SET
            first_name = #{firstName},
            last_name = #{lastName},
            email = #{email},
            roles = #{role}
        WHERE admin_id = #{adminId}
        """)
    int update(AdminEntity admin);


    @Delete("""
        DELETE FROM admins
        WHERE admin_id = #{adminId}
        """)
    int deleteById(Long adminId);
}
