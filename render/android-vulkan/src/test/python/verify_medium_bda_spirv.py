#!/usr/bin/env python3
"""Check the compiled medium BDA shader's feature and native-buffer contracts.

This bounded reader checks the relevant SPIR-V declarations; it is not a full
SPIR-V validator. Opcode/enumerant values are defined by Khronos SPIRV-Headers:
https://github.com/KhronosGroup/SPIRV-Headers/blob/main/include/spirv/unified1/spirv.h
"""

import argparse
import hashlib
import json
from pathlib import Path
import struct


CAPABILITIES = {
    1: "Shader",
    61: "GroupNonUniform",
    64: "GroupNonUniformBallot",
    5347: "PhysicalStorageBufferAddresses",
}
PUSH_OFFSETS = (0, 4, 8, 12, 16, 24, 32)


def require(condition, message):
    if not condition:
        raise ValueError(message)


def read_instructions(binary):
    require(20 <= len(binary) <= 1024 * 1024 and len(binary) % 4 == 0,
            "SPIR-V must contain a bounded whole-word module")
    words = struct.unpack(f"<{len(binary) // 4}I", binary)
    require(words[0] == 0x07230203, "invalid SPIR-V magic")
    require(0x00010000 <= words[1] <= 0x00010300,
            "module version exceeds Vulkan 1.1's SPIR-V 1.3 contract")
    require(words[4] == 0, "invalid reserved SPIR-V header word")
    result = []
    position = 5
    while position < len(words):
        size, opcode = words[position] >> 16, words[position] & 0xffff
        require(size > 0 and position + size <= len(words), "truncated SPIR-V instruction")
        result.append((opcode, words[position + 1:position + size]))
        position += size
    return result


def verify(binary):
    instructions = read_instructions(binary)
    capabilities = {args[0] for op, args in instructions if op == 17 and len(args) == 1}
    require(capabilities == set(CAPABILITIES),
            f"unexpected shader capabilities: {sorted(capabilities)}; Int64 must not be required")
    integers = {args[0]: args[1:] for op, args in instructions if op == 21 and len(args) == 3}
    require(all(bits != 64 for bits, _ in integers.values()), "64-bit shader integers are not enabled")
    pointers = {args[0]: args[1:] for op, args in instructions if op == 32 and len(args) == 3}
    structures = {args[0]: args[1:] for op, args in instructions if op == 30 and args}
    arrays = {args[0]: args[1] for op, args in instructions if op == 29 and len(args) == 2}
    strides = {args[0]: args[2] for op, args in instructions if op == 71 and len(args) == 3 and args[1] == 6}
    offsets = {}
    for op, args in instructions:
        if op == 72 and len(args) == 4 and args[2] == 35:
            members = offsets.setdefault(args[0], {})
            require(args[1] not in members, "duplicate member-offset decoration")
            members[args[1]] = args[3]

    def members_at(structure, expected):
        members = structures.get(structure, ())
        require(len(members) == len(expected), "unexpected buffer member count")
        require(offsets.get(structure) == dict(enumerate(expected)),
                f"native member offsets changed for SPIR-V structure {structure}")
        return members

    push_variables = [args for op, args in instructions if op == 59 and len(args) >= 3 and args[2] == 9]
    require(len(push_variables) == 1, "expected exactly one push-constant variable")
    push_pointer = pointers.get(push_variables[0][0])
    require(push_pointer is not None and push_pointer[0] == 9, "invalid push-constant pointer")
    members = members_at(push_pointer[1], PUSH_OFFSETS)
    require(all(integers.get(member) == (32, 0) for member in members[:4]),
            "push-constant prefix must be four unsigned 32-bit integers")
    address_pointers = [pointers.get(member) for member in members[4:]]
    require(all(pointer is not None and pointer[0] == 5349 for pointer in address_pointers),
            "three physical-storage pointer addresses are required")

    for pointer, stride in zip(address_pointers[:2], (40, 20)):
        block = members_at(pointer[1], (0,))
        require(block[0] in arrays and strides.get(block[0]) == stride,
                f"native record array stride must remain {stride} bytes")
        members_at(arrays[block[0]], tuple(range(0, stride, 4)))
    draw = members_at(address_pointers[2][1], (0, 4, 8, 12))
    require(all(integers.get(member) == (32, 0) for member in draw),
            "indirect draw command must retain four unsigned 32-bit fields")
    return {
        "schema": "solarlab.medium-bda-spirv.v1",
        "sha256": hashlib.sha256(binary).hexdigest(),
        "capabilities": [CAPABILITIES[value] for value in sorted(capabilities)],
        "requiresShaderInt64": False,
        "pushConstantBytes": 40,
        "pushMemberOffsets": list(PUSH_OFFSETS),
        "sourceArrayStride": 40,
        "outputArrayStride": 20,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("shader", type=Path)
    args = parser.parse_args()
    try:
        # Read at most one byte beyond the supported bound, even for a bad input.
        with args.shader.open("rb") as stream:
            result = verify(stream.read(1024 * 1024 + 1))
    except (OSError, ValueError) as error:
        parser.exit(1, f"BDA SPIR-V contract failed: {error}\n")
    print(json.dumps(result, sort_keys=True))


if __name__ == "__main__":
    main()
