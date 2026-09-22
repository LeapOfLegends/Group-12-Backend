package group12.Repository;

import java.util.List;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import group12.Entities.ClientEntity;

@Mapper
public interface ClientRepository {

    @Select("SELECT client_id, first_name, last_name, email, password_hash, ssn, phone_number, account_balance, created_at FROM clients WHERE client_id = #{clientId}")
    ClientEntity findById(Long clientId);

    @Select("SELECT client_id, first_name, last_name, email, password_hash, ssn, phone_number, account_balance, created_at FROM clients WHERE email = #{email}")
    ClientEntity findByEmail(String email);

    @Select("SELECT client_id, first_name, last_name, email, password_hash, ssn, phone_number, account_balance, created_at FROM clients ORDER BY client_id")
    List<ClientEntity> findAll();

    @Insert("INSERT INTO clients (first_name, last_name, email, password_hash, ssn, phone_number, account_balance) VALUES (#{firstName}, #{lastName}, #{email}, #{passwordHash}, #{ssn}, #{phoneNumber}, #{accountBalance})")
    @Options(useGeneratedKeys = true, keyProperty = "clientId", keyColumn = "client_id")
    int save(ClientEntity client);

    @Update("UPDATE clients SET first_name = #{firstName}, last_name = #{lastName}, email = #{email}, password_hash = #{passwordHash}, ssn = #{ssn}, phone_number = #{phoneNumber}, account_balance = #{accountBalance} WHERE client_id = #{clientId}")
    int update(ClientEntity client);

    @Delete("DELETE FROM clients WHERE client_id = #{clientId}")
    int deleteById(Long clientId);
}
