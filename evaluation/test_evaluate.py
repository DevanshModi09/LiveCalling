import unittest
from evaluate import evaluate

class EvaluationTests(unittest.TestCase):
    def case(self):
        return {'id':'partial_0_clean','base_id':'partial_0','category':'partial','condition':'clean','duration_s':2,'labels_0_5s':[0,0,1,1]}
    def test_abstentions_are_not_hidden_from_coverage(self):
        row=self.case(); pred={'id':row['id'],'bins':[{'start_s':1,'end_s':1.5,'score':.9,'evidence':'READY'}]}
        report=evaluate([row],[pred],.7)
        self.assertEqual(report['groups']['all']['coverage'],.25)
        self.assertEqual(report['groups']['all']['recall_including_abstentions'],.5)
        self.assertIsNone(report['timing']['meets_500ms_e2e'])
        self.assertEqual(report['timing']['cadence_failures'],1)
    def test_late_results_do_not_pass_latency_gate(self):
        row=self.case(); updates=[{'audio_time_s':i*.5,'completed_wall_ms':i*500+800,'audio_captured_wall_ms':i*500,'inference_ms':700,'evidence':'READY'} for i in range(1,5)]
        report=evaluate([row],[{'id':row['id'],'bins':[],'updates':updates}],.7)
        self.assertEqual(report['timing']['cadence_failures'],0)
        self.assertFalse(report['timing']['meets_500ms_e2e'])
    def test_forced_abstention_scores_are_rejected(self):
        row=self.case()
        with self.assertRaises(ValueError):evaluate([row],[{'id':row['id'],'bins':[{'start_s':0,'end_s':.5,'score':.1,'evidence':'INSUFFICIENT_SPEECH'}]}],.7)
    def test_status_ticks_alone_cannot_pass_real_time_gate(self):
        row=self.case();updates=[{'audio_time_s':i*.5,'completed_wall_ms':i*500,'audio_captured_wall_ms':i*500,'inference_ms':0,'evidence':'NO_MODEL'} for i in range(1,5)]
        report=evaluate([row],[{'id':row['id'],'bins':[],'updates':updates}],.7)
        self.assertIsNone(report['timing']['meets_500ms_e2e'])
    def test_no_predictions_do_not_claim_model_validation(self):
        report=evaluate([self.case()],[],.7)
        self.assertFalse(report['model_supplied']); self.assertEqual(report['missing_cases'],1)
if __name__=='__main__':unittest.main()
