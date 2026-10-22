package br.com.vovolinux.m2m.machine;

import java.util.List;

/**
 * Quem usa uma máquina (perfis, agendamentos, armazenamento...). Cada módulo registra a sua fonte; a remoção é
 * recusada enquanto alguma devolver usos.
 */
public interface MachineUsageSource {

    /** Descrições legíveis dos usos (ex.: "Perfil Trabalho (origem)"). */
    List<String> usagesOf(long ownerId, long machineId);
}
