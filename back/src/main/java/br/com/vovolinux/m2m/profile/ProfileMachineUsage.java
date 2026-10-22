package br.com.vovolinux.m2m.profile;

import br.com.vovolinux.m2m.machine.MachineUsageSource;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Perfis, mapeamentos e agendamentos que usam uma máquina: impedem a remoção dela. */
@Component
public class ProfileMachineUsage implements MachineUsageSource {

    private final JdbcClient jdbc;

    public ProfileMachineUsage(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<String> usagesOf(long ownerId, long machineId) {
        List<String> out = new ArrayList<>();
        jdbc.sql("select name from profile where owner_id = :o and source_machine_id = :m order by name")
                .param("o", ownerId).param("m", machineId).query(String.class).list()
                .forEach(n -> out.add("Perfil " + n + " (origem)"));
        jdbc.sql("select name from profile where owner_id = :o and storage_machine_id = :m order by name")
                .param("o", ownerId).param("m", machineId).query(String.class).list()
                .forEach(n -> out.add("Perfil " + n + " (armazenamento)"));
        jdbc.sql("""
                select distinct p.name from profile_dir_mapping m
                  join profile_dir d on d.id = m.profile_dir_id join profile p on p.id = d.profile_id
                 where p.owner_id = :o and m.machine_id = :m order by p.name""")
                .param("o", ownerId).param("m", machineId).query(String.class).list()
                .forEach(n -> out.add("Perfil " + n + " (mapeamento de diretórios)"));
        jdbc.sql("""
                select p.name from sync_schedule s join profile p on p.id = s.profile_id
                 where p.owner_id = :o and s.dest_machine_id = :m order by p.name""")
                .param("o", ownerId).param("m", machineId).query(String.class).list()
                .forEach(n -> out.add("Perfil " + n + " (sincronização agendada)"));
        return out;
    }
}
