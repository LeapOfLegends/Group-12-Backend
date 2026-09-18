package group12.Repository;

import org.apache.ibatis.annotations.*;
import group12.Entities.ClientEntity;
import java.util.List;

@Mapper
public interface ClientMapper {
    @Select("SELECT id, name FROM clients WHERE id = #{id}")
    ClientEntity findById(Long id);

    @Select("SELECT id, name FROM clients")
    List<ClientEntity> findAll();

    @Insert("INSERT INTO clients(name) VALUES(#{name})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ClientEntity client);

    @Update("UPDATE clients SET name = #{name} WHERE id = #{id}")
    int update(ClientEntity client);

    @Delete("DELETE FROM clients WHERE id = #{id}")
    int delete(Long id);
}
