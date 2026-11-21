import importlib.util
import struct
import unittest
from pathlib import Path

spec=importlib.util.spec_from_file_location('dex_invokes', Path(__file__).resolve().parents[2]/'scripts/ci/dex_invokes.py')
module=importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

class InvokeTest(unittest.TestCase):
    def scan(self, words, parameters, regs=300, outs=300):
        # An isolated code_item, using a deterministic referenced prototype.
        dex=object.__new__(module.Dex)
        dex.data=struct.pack('<4H2I',regs,0,outs,0,0,len(words))+struct.pack('<%dH'%len(words),*words)
        dex.method=lambda _:('LTarget;','<init>',0)
        dex.param_words=lambda _:parameters
        return list(dex.inspect(0))

    def test_maximum_encodable_instance_call(self):
        self.assertEqual([],self.scan([0xff76,0,0,0x000e],254))
    def test_wrapped_counts_fail_as_in_rejected_17211(self):
        for encoded,parameter_words in [(0,255),(1,256)]:
            with self.subTest(encoded=encoded,parameter_words=parameter_words):
                issues=self.scan([encoded<<8|0x76,0,0,0x000e],parameter_words)
                self.assertEqual(1,len(issues))
                self.assertIn(f'count={encoded} expected={parameter_words+1}',issues[0][1])
    def test_static_zero_arguments_is_legal(self):
        self.assertEqual([],self.scan([0x0077,0,0,0x000e],0))
    def test_instance_call_always_has_receiver(self):
        self.assertTrue(self.scan([0x0076,0,0,0x000e],0))
    def test_range_exceeding_register_file_fails(self):
        self.assertTrue(self.scan([0x0276,0,299,0x000e],1))
    def test_data_payload_is_not_executable_opcode(self):
        self.assertEqual([],self.scan([0x0300,2,3,0,0x0076,0x0076,0x0076,0x000e],0))
    def test_truncated_instruction_fails(self):
        with self.assertRaises(module.DexError):self.scan([0x0076],0)

if __name__=='__main__':unittest.main()
